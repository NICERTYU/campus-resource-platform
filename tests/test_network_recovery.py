"""Failure recovery using both real application jars and PostgreSQL.

The fault proxy forwards identity to the real resource application and selectively
fails restriction responses. A separate workflow schema prevents contamination.
Opt in with CAMPUS_BASE_URL and CAMPUS_NETWORK_TEST=1 on scripts/dev.sh stack.
"""
import datetime as dt
import http.server
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time
import unittest
import urllib.error
import urllib.request
import uuid

from test_system_http import Client, BASE_URL, SHANGHAI

ROOT = Path(__file__).resolve().parents[1]


class FaultProxy(http.server.BaseHTTPRequestHandler):
    mode = "normal"
    upstream = "http://127.0.0.1:8081"
    def log_message(self, *args):
        pass
    def do_GET(self):
        self.forward()
    def do_POST(self):
        self.forward()
    def do_PUT(self):
        self.forward()
    def forward(self):
        data = self.rfile.read(int(self.headers.get("Content-Length", "0"))) or None
        restriction = self.path.startswith("/internal/v1/restrictions/")
        if restriction and self.mode == "unavailable":
            self.failure()
            return
        headers = {key: value for key, value in self.headers.items()
                   if key.lower() not in ("host", "content-length", "connection")}
        request = urllib.request.Request(self.upstream + self.path, data=data, headers=headers, method=self.command)
        try:
            result = urllib.request.urlopen(request, timeout=15)
        except urllib.error.HTTPError as error:
            result = error
        with result:
            body = result.read()
            status = result.status
            content_type = result.headers.get("Content-Type", "application/json")
        if restriction and self.mode == "response-lost" and status < 300:
            # The real resource transaction has committed. Close without any response.
            self.close_connection = True
            self.connection.shutdown(socket.SHUT_RDWR)
            self.connection.close()
            return
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def failure(self):
        body = json.dumps({"code": "TEST_UNAVAILABLE", "message": "资源服务暂时不可用", "requestId": "fault-test"}).encode()
        self.send_response(503)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


@unittest.skipUnless(BASE_URL and os.environ.get("CAMPUS_NETWORK_TEST") == "1", "opt in CAMPUS_NETWORK_TEST=1 on actual local stack")
class RealNetworkRecovery(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schema = "workflow_fault_" + uuid.uuid4().hex[:10]
        cls.schema_created = False
        cls.source_ids = []
        cls.room_ids = []
        cls.addClassCleanup(cls.cleanup)
        cls.proxy = http.server.ThreadingHTTPServer(("127.0.0.1", 0), FaultProxy)
        threading.Thread(target=cls.proxy.serve_forever, daemon=True).start()
        cls.env = os.environ.copy()
        for line in (ROOT / ".local/dev.env").read_text().splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                cls.env[key] = value
        cls.sql("CREATE SCHEMA %s AUTHORIZATION campus_workflow" % cls.schema)
        cls.schema_created = True
        cls.env.update({"DB_URL": "jdbc:postgresql://127.0.0.1:%s/campus?currentSchema=%s" % (cls.env.get("DB_PORT", "54329"), cls.schema),
                        "DB_USERNAME": "campus_workflow", "DB_PASSWORD": cls.env["WORKFLOW_DB_PASSWORD"],
                        "RESOURCE_URL": "http://127.0.0.1:%s" % cls.proxy.server_port, "RETRY_DELAY_MS": "250"})
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            cls.port = listener.getsockname()[1]
        cls.log = open(ROOT / ".local/fault-workflow.log", "a")
        cls.launch()
        # Source IDs must be unique across the deliberately isolated test workflow.
        sequence_start = int(uuid.uuid4().hex[:7], 16) + 1000000000
        for table in ("classroom_applications", "repair_orders"):
            cls.sql("SELECT setval(pg_get_serial_sequence('%s.%s','id'), %s)" % (cls.schema, table, sequence_start))
        cls.admin = Client("admin")

    @classmethod
    def sql(cls, query):
        return subprocess.run(["docker", "exec", "campus-dev-db-1", "psql", "-v", "ON_ERROR_STOP=1", "-U", "campus_owner", "-d", "campus", "-tAc", query],
                              check=True, capture_output=True, text=True).stdout.strip()
    @classmethod
    def launch(cls):
        java = "java"
        if os.name == "posix" and os.uname().sysname == "Darwin":
            java_home = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip()
            java = java_home + "/bin/java"
        cls.process = subprocess.Popen([java, "-jar", str(ROOT / "backend/workflow-app/target/workflow-app-0.1.0.jar"),
            "--server.port=%s" % cls.port, "--spring.flyway.schemas=%s" % cls.schema,
            "--spring.flyway.default-schema=%s" % cls.schema,
            "--spring.datasource.hikari.connection-init-sql=SET search_path TO %s" % cls.schema],
            env=cls.env, stdout=cls.log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if cls.process.poll() is not None:
                raise RuntimeError("fault workflow startup failed, see .local/fault-workflow.log")
            try:
                with urllib.request.urlopen("http://127.0.0.1:%s/actuator/health" % cls.port, timeout=1) as response:
                    if response.status == 200:
                        return
            except (urllib.error.URLError, TimeoutError):
                time.sleep(0.2)
        raise RuntimeError("fault workflow health timeout")
    @classmethod
    def stop(cls):
        cls.process.terminate()
        try:
            cls.process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            cls.process.kill()
            cls.process.wait(timeout=5)
    @classmethod
    def cleanup(cls):
        # unittest calls class cleanup even if setUpClass fails partway through.
        if hasattr(cls, "process") and cls.process.poll() is None:
            cls.stop()
        if hasattr(cls, "log"):
            cls.log.close()
        if hasattr(cls, "proxy"):
            cls.proxy.shutdown()
            cls.proxy.server_close()
        # Remove only this class's generated resource fixtures after stopping retries.
        # Otherwise a dropped workflow schema would leave orphaned active restrictions.
        if cls.source_ids:
            ids = ",".join(str(value) for value in cls.source_ids)
            cls.sql("DELETE FROM resource.audit_logs WHERE object_type='M2' AND object_id IN (%s); DELETE FROM resource.restrictions WHERE source_type='M2' AND source_id IN (%s)" % (ids, ids))
        if cls.room_ids:
            ids = ",".join(str(value) for value in cls.room_ids)
            cls.sql("DELETE FROM resource.audit_logs WHERE object_type='classroom' AND object_id IN (%s); DELETE FROM resource.classrooms WHERE id IN (%s)" % (ids, ids))
        if cls.schema_created:
            cls.sql("DROP SCHEMA IF EXISTS %s CASCADE" % cls.schema)
        FaultProxy.mode = "normal"

    def request(self, method, path, body=None, key=None):
        headers = {"X-CSRF-TOKEN": self.admin.csrf, "Content-Type": "application/json"}
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request("http://127.0.0.1:%s/api/v1%s" % (self.port, path),
                 headers=headers, data=None if body is None else json.dumps(body).encode(), method=method)
        try:
            result = self.admin.opener.open(request, timeout=20)
        except urllib.error.HTTPError as error:
            result = error
        with result:
            return result.status, json.loads(result.read())
    def application(self):
        status, room = self.admin.request("POST", "/admin/classrooms", {"name": "网络恢复-" + uuid.uuid4().hex[:8],
            "building": "测试楼", "floor": 1, "department": None, "openTime": "08:00", "closeTime": "22:00", "enabled": True})
        self.assertIn(status, (200, 201), room)
        self.room_ids.append(room["id"])
        start = (dt.datetime.now(SHANGHAI) + dt.timedelta(days=3)).replace(hour=14, minute=0, second=0, microsecond=0)
        status, record = self.request("POST", "/classroom-applications", {"classroomId": room["id"],
            "startTime": start.isoformat(), "endTime": (start + dt.timedelta(hours=1)).isoformat(), "purpose": "实际HTTP故障恢复"})
        self.assertEqual(status, 201, record)
        self.source_ids.append(record["id"])
        if record.get("totalSteps", 1) > 1:
            status, record = self.request("POST", "/classroom-applications/%s/approve" % record["id"],
                                          {"comment": "初审通过", "revision": record["revision"]})
            self.assertEqual(status, 200, record)
            self.assertEqual(record["currentStep"], 2)
        return record
    def eventually_approved(self, application):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            status, records = self.request("GET", "/classroom-applications")
            self.assertEqual(status, 200)
            record = next(row for row in records if row["id"] == application["id"])
            if record["status"] == "APPROVED":
                return record
            time.sleep(0.2)
        self.fail("outbox did not recover to APPROVED")
    def source_count(self, application):
        return int(self.sql("SELECT count(*) FROM resource.restrictions WHERE source_type='M2' AND source_id=%s AND active" % application["id"]))

    def test_T15_real_resource_unavailable_then_restart_recovers(self):
        application = self.application()
        FaultProxy.mode = "unavailable"
        status, record = self.request("POST", "/classroom-applications/%s/approve" % application["id"], {"comment": "通过", "revision": application["revision"]})
        self.assertEqual(status, 202, record)
        self.assertEqual(record["status"], "PROCESSING")
        self.assertEqual(self.source_count(application), 0)
        self.stop()
        FaultProxy.mode = "normal"
        self.launch()
        self.eventually_approved(application)
        self.assertEqual(self.source_count(application), 1)

    def test_T15_real_resource_committed_response_lost_retries_once(self):
        application = self.application()
        FaultProxy.mode = "response-lost"
        status, record = self.request("POST", "/classroom-applications/%s/approve" % application["id"], {"comment": "通过", "revision": application["revision"]}, key=uuid.uuid4().hex)
        self.assertEqual(status, 202, record)
        self.assertEqual(record["status"], "PROCESSING")
        self.assertEqual(self.source_count(application), 1)
        FaultProxy.mode = "normal"
        self.eventually_approved(application)
        self.assertEqual(self.source_count(application), 1)
        self.assertEqual(self.request("POST", "/classroom-applications/%s/withdraw" % application["id"], {})[0], 200)
        self.assertEqual(self.source_count(application), 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
