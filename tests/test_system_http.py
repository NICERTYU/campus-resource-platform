"""Black-box acceptance against the actual two apps, never an in-memory substitute.

Run with CAMPUS_BASE_URL=http://127.0.0.1:5173 after explicit demo seeding.
Each test creates its own public classroom, so the suite can be repeated.
"""
import concurrent.futures
import datetime as dt
import http.cookiejar
import json
import os
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE_URL = os.environ.get("CAMPUS_BASE_URL", "").rstrip("/")
SHANGHAI = dt.timezone(dt.timedelta(hours=8))


class Client:
    def __init__(self, username):
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.csrf = self.request("GET", "/auth/csrf")[1]["token"]
        status, self.user = self.request("POST", "/auth/login", {"username": username, "password": "Campus123!"})
        if status != 200:
            raise AssertionError((status, self.user))
        self.csrf = self.request("GET", "/auth/csrf")[1]["token"]

    def request(self, method, path, body=None, key=None, csrf=True):
        headers = {"Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if method != "GET" and csrf:
            headers["X-CSRF-TOKEN"] = getattr(self, "csrf", "")
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(BASE_URL + "/api/v1" + path,
                                         data=None if body is None else json.dumps(body).encode(),
                                         headers=headers, method=method)
        try:
            response = self.opener.open(request, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            content = response.read()
            return response.status, json.loads(content) if content else None


@unittest.skipUnless(BASE_URL, "set CAMPUS_BASE_URL to run the real HTTP acceptance suite")
class SystemHttpAcceptance(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.admin = Client("admin")
        cls.student = Client("student")
        cls.student2 = Client("student2")
        cls.applicant = Client("applicant")
        cls.technician = Client("technician")
        cls.outsider = Client("outsider")

    def setUp(self):
        self.prefix = uuid.uuid4().hex[:10]
        status, room = self.admin.request("POST", "/admin/classrooms", {
            "name": "验收-" + self.prefix, "building": "测试楼", "floor": 1,
            "department": None, "openTime": "08:00", "closeTime": "22:00", "enabled": True,
        })
        self.assertIn(status, (200, 201), room)
        self.room = room["id"]
        self.seats = []
        for index in range(2):
            status, seat = self.admin.request("POST", "/admin/classrooms/%s/seats" % self.room,
                {"label": "%s-%s" % (self.prefix, index), "hasPower": index == 0, "enabled": True})
            self.assertIn(status, (200, 201), seat)
            self.seats.append(seat["id"])
        self.start = (dt.datetime.now(SHANGHAI) + dt.timedelta(days=2)).replace(hour=10, minute=0, second=0, microsecond=0)
        self.end = self.start + dt.timedelta(hours=1)
        # Create isolated rooms, but the overlapping-user rule also requires clean bookings.
        for client in (self.student, self.student2):
            status, records = client.request("GET", "/reservations/mine?statusGroup=current&pageSize=200")
            self.assertEqual(status, 200)
            for record in records:
                if record["status"] in ("PENDING", "CHECKED_IN"):
                    client.request("POST", "/reservations/%s/cancel" % record["id"], {})

    def tearDown(self):
        # Leave generated history for diagnosis, but release this test's active
        # personal bookings so a later browser flow can book the same user.
        for client in (self.student, self.student2):
            status, records = client.request("GET", "/reservations/mine?statusGroup=current&pageSize=200")
            self.assertEqual(status, 200)
            for record in records:
                if record["classroomId"] == self.room and record["status"] in ("PENDING", "CHECKED_IN"):
                    status, result = client.request("POST", "/reservations/%s/cancel" % record["id"], {})
                    self.assertEqual(status, 200, result)

    def reservation(self, client=None, seat=None, start=None, end=None, key=None):
        return (client or self.student).request("POST", "/reservations", {
            "seatId": seat or self.seats[0], "startTime": (start or self.start).isoformat(),
            "endTime": (end or self.end).isoformat(),
        }, key=key)

    def application(self):
        status, record = self.applicant.request("POST", "/classroom-applications", {
            "classroomId": self.room, "startTime": self.start.isoformat(),
            "endTime": self.end.isoformat(), "purpose": "HTTP跨模块验收 " + self.prefix,
        })
        self.assertIn(status, (200, 201), record)
        return record

    def repair(self):
        status, record = self.student.request("POST", "/repair-orders", {
            "classroomId": self.room, "description": "验收插座故障 " + self.prefix,
        })
        self.assertIn(status, (200, 201), record)
        status, record = self.admin.request("POST", "/repair-orders/%s/assign" % record["id"],
                                           {"technicianId": self.technician.user["id"]})
        self.assertEqual(status, 200, record)
        status, record = self.technician.request("POST", "/repair-orders/%s/start" % record["id"], {})
        self.assertEqual(status, 200, record)
        self.assertEqual(record["status"], "IN_PROGRESS", record)
        return record

    def accept(self, repair):
        status, record = self.technician.request("POST", "/repair-orders/%s/complete" % repair["id"], {"note": "已修复"})
        self.assertEqual(status, 200, record)
        self.assertEqual(record["status"], "AWAITING_ACCEPTANCE")
        status, record = self.admin.request("POST", "/repair-orders/%s/accept" % repair["id"], {"comment": "通过"})
        self.assertEqual(status, 200, record)
        self.assertEqual(record["status"], "ACCEPTED")

    def availability(self):
        query = urllib.parse.urlencode({"classroomId": self.room, "startTime": self.start.isoformat(), "endTime": self.end.isoformat()})
        status, seats = self.student.request("GET", "/seats/availability?" + query)
        self.assertEqual(status, 200, seats)
        return seats

    def test_T01_same_seat_concurrency_and_adjacent_hours(self):
        barrier = threading.Barrier(2)
        def submit(client):
            barrier.wait(timeout=5)
            return self.reservation(client=client)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            jobs = [pool.submit(submit, client) for client in (self.student, self.student2)]
            responses = [job.result(timeout=25) for job in jobs]
        self.assertEqual(sum(status in (200, 201) for status, _ in responses), 1, responses)
        self.assertEqual(sum(status == 409 for status, _ in responses), 1, responses)
        status, record = self.reservation(start=self.end, end=self.end + dt.timedelta(hours=1))
        self.assertIn(status, (200, 201), record)

    def test_T02_approval_and_reservation_race(self):
        application = self.application()
        barrier = threading.Barrier(2)
        def book():
            barrier.wait(timeout=5)
            return self.reservation()
        def approve():
            barrier.wait(timeout=5)
            return self.admin.request("POST", "/classroom-applications/%s/approve" % application["id"], {"comment": "同意"})
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            jobs = [pool.submit(book), pool.submit(approve)]
            results = [job.result(timeout=25) for job in jobs]
        successes = sum(status in (200, 201) and data.get("status") != "PROCESSING" for status, data in results)
        self.assertEqual(successes, 1, results)
        self.assertIn(409, [status for status, _ in results], results)

    def test_T03_real_approval_withdrawal_and_rejection(self):
        application = self.application()
        status, result = self.admin.request("POST", "/classroom-applications/%s/approve" % application["id"], {"comment": "同意"})
        self.assertEqual(status, 200, result)
        self.assertEqual(result["status"], "APPROVED")
        self.assertTrue(all(not seat["available"] for seat in self.availability()))
        self.assertEqual(self.reservation()[0], 409)
        status, result = self.applicant.request("POST", "/classroom-applications/%s/withdraw" % application["id"], {})
        self.assertEqual(status, 200, result)
        self.assertEqual(result["status"], "WITHDRAWN")
        self.assertTrue(all(seat["available"] for seat in self.availability()))
        application = self.application()
        status, result = self.admin.request("POST", "/classroom-applications/%s/reject" % application["id"], {"comment": "用途不符"})
        self.assertEqual(status, 200, result)
        self.assertEqual(result["status"], "REJECTED")
        self.assertTrue(all(seat["available"] for seat in self.availability()))

    def test_T04_T05_two_repairs_and_approved_application(self):
        application = self.application()
        self.assertEqual(self.admin.request("POST", "/classroom-applications/%s/approve" % application["id"], {"comment": "同意"})[0], 200)
        first, second = self.repair(), self.repair()
        self.assertTrue(all(not seat["available"] for seat in self.availability()))
        self.accept(first)
        self.assertTrue(all(not seat["available"] for seat in self.availability()))
        self.accept(second)
        self.assertTrue(all(not seat["available"] for seat in self.availability()))
        self.assertEqual(self.applicant.request("POST", "/classroom-applications/%s/withdraw" % application["id"], {})[0], 200)
        self.assertTrue(all(seat["available"] for seat in self.availability()))

    def test_T04_completion_and_return_keep_repair_restriction(self):
        repair = self.repair()
        path = "/repair-orders/%s" % repair["id"]
        self.assertEqual(self.technician.request("POST", path + "/complete", {"note": "提交"})[0], 200)
        self.assertTrue(all(not seat["available"] for seat in self.availability()))
        status, returned = self.admin.request("POST", path + "/return", {"comment": "需返修"})
        self.assertEqual(status, 200, returned)
        self.assertEqual(returned["status"], "IN_PROGRESS")
        self.assertTrue(all(not seat["available"] for seat in self.availability()))
        self.accept(repair)
        self.assertTrue(all(seat["available"] for seat in self.availability()))

    def test_T08_roles_ownership_csrf_and_department(self):
        status, booking = self.reservation()
        self.assertIn(status, (200, 201), booking)
        self.assertEqual(self.student2.request("POST", "/reservations/%s/cancel" % booking["id"], {})[0], 403)
        application = self.application()
        self.assertEqual(self.student.request("POST", "/classroom-applications/%s/approve" % application["id"], {"comment": "恶意"})[0], 403)
        self.assertEqual(self.admin.request("POST", "/classroom-applications/%s/approve" % application["id"], {"comment": "缺少CSRF"}, csrf=False)[0], 403)
        status, rooms = self.admin.request("GET", "/classrooms")
        self.assertEqual(status, 200)
        restricted = next(room for room in rooms if room["department"] == "CS")
        query = urllib.parse.urlencode({"classroomId": restricted["id"], "startTime": self.start.isoformat(), "endTime": self.end.isoformat()})
        self.assertEqual(self.outsider.request("GET", "/seats/availability?" + query)[0], 403)

    def test_T14_repair_start_racing_booking_blocks_subsequent_requests(self):
        status, repair = self.student.request("POST", "/repair-orders", {
            "classroomId": self.room, "description": "维修与预约并发验收",
        })
        self.assertIn(status, (200, 201), repair)
        status, repair = self.admin.request("POST", "/repair-orders/%s/assign" % repair["id"],
            {"technicianId": self.technician.user["id"]})
        self.assertEqual(status, 200, repair)
        barrier = threading.Barrier(2)
        def start_repair():
            barrier.wait(timeout=5)
            return self.technician.request("POST", "/repair-orders/%s/start" % repair["id"], {})
        def book():
            barrier.wait(timeout=5)
            return self.reservation()
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            jobs = [pool.submit(start_repair), pool.submit(book)]
            started, booked = [job.result(timeout=25) for job in jobs]
        self.assertEqual(started[0], 200, started)
        self.assertEqual(started[1]["status"], "IN_PROGRESS")
        self.assertIn(booked[0], (200, 201, 409), booked)
        self.assertTrue(all(not seat["available"] for seat in self.availability()))
        self.assertEqual(self.reservation(client=self.student2, seat=self.seats[1])[0], 409)
        if booked[0] in (200, 201):
            records = self.student.request("GET", "/reservations/mine")[1]
            retained = next(record for record in records if record["id"] == booked[1]["id"])
            self.assertEqual(retained["status"], "PENDING")
        self.accept(repair)

    def test_T10_hours_duration_and_power_filter(self):
        self.assertEqual(self.reservation(start=self.start.replace(minute=15))[0], 400)
        self.assertEqual(self.reservation(end=self.start + dt.timedelta(hours=5))[0], 400)
        self.assertEqual(self.reservation(start=self.start.replace(hour=7), end=self.start.replace(hour=8))[0], 400)
        query = urllib.parse.urlencode({"classroomId": self.room, "startTime": self.start.isoformat(), "endTime": self.end.isoformat(), "hasPower": "true"})
        status, seats = self.student.request("GET", "/seats/availability?" + query)
        self.assertEqual(status, 200, seats)
        self.assertEqual(len(seats), 1)
        self.assertTrue(seats[0]["hasPower"])

    def test_T13_idempotent_booking_and_payload_conflict(self):
        key = uuid.uuid4().hex
        first = self.reservation(key=key)
        second = self.reservation(key=key)
        self.assertEqual(first, second)
        self.assertIn(first[0], (200, 201))
        self.assertEqual(self.reservation(seat=self.seats[1], key=key)[0], 409)

    def test_T13_idempotent_workflow_create(self):
        key = uuid.uuid4().hex
        body = {"classroomId": self.room, "description": "故障幂等验收"}
        first = self.student.request("POST", "/repair-orders", body, key=key)
        second = self.student.request("POST", "/repair-orders", body, key=key)
        self.assertEqual(first, second)
        body["description"] = "不同请求"
        self.assertEqual(self.student.request("POST", "/repair-orders", body, key=key)[0], 409)


if __name__ == "__main__":
    unittest.main(verbosity=2)
