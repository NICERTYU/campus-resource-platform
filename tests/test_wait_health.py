"""Readiness waiting through the shell CLI and real local HTTP responses."""
from contextlib import contextmanager
import http.server
import os
from pathlib import Path
import signal
import subprocess
import threading
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/wait-health.sh"


class HealthHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        with self.server.lock:
            self.server.requests += 1
            attempt = self.server.requests
        if self.path == "/stall":
            self.server.release.wait(10)
        status = 503 if self.path == "/unavailable" or (self.path == "/warming" and attempt == 1) else 200
        try:
            self.send_response(status)
            self.end_headers()
            self.wfile.write(b"ready" if status == 200 else b"warming up")
        except (BrokenPipeError, ConnectionResetError):
            pass


@contextmanager
def health_server():
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), HealthHandler)
    server.daemon_threads = True
    server.requests = 0
    server.lock = threading.Lock()
    server.release = threading.Event()
    worker = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01), daemon=True)
    worker.start()
    try:
        yield server, "http://127.0.0.1:%s" % server.server_port
    finally:
        server.release.set()
        server.shutdown()
        server.server_close()
        worker.join(timeout=1)


class WaitHealthTests(unittest.TestCase):
    def run_wait(self, *arguments, limit=4):
        started = time.monotonic()
        process = subprocess.Popen([str(SCRIPT), *arguments], stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True, start_new_session=True)
        try:
            stdout, stderr = process.communicate(timeout=limit)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.communicate()
            self.fail("readiness wait exceeded its bounded test deadline")
        return process.returncode, stdout, stderr, time.monotonic() - started

    def test_rejects_invalid_timeout_before_contacting_server(self):
        with health_server() as (server, base):
            for value in ("0", "-1", "1.5", "invalid", "", "86401", "999999999999999999999"):
                with self.subTest(timeout=value):
                    code, _, error, _ = self.run_wait(base + "/ready", value)
                    self.assertEqual(code, 2, error)
            self.assertEqual(server.requests, 0)

    def test_ready_server_succeeds_with_default_and_explicit_timeout(self):
        with health_server() as (_, base):
            for arguments in ((base + "/ready",), (base + "/ready", "86400")):
                with self.subTest(arguments=arguments):
                    code, output, error, _ = self.run_wait(*arguments)
                    self.assertEqual(code, 0, error)
                    self.assertEqual(output, "")

    def test_retries_until_server_becomes_ready(self):
        with health_server() as (_, base):
            code, _, error, _ = self.run_wait(base + "/warming", "3")
            self.assertEqual(code, 0, error)

    def test_unavailable_server_exits_at_configured_timeout(self):
        with health_server() as (_, base):
            code, _, error, elapsed = self.run_wait(base + "/unavailable", "1", limit=3)
            self.assertEqual(code, 1, error)
            self.assertIn("服务未就绪", error)
            self.assertLess(elapsed, 2.5)

    def test_rejects_invalid_url_and_argument_count(self):
        with health_server() as (server, base):
            for arguments in ((), ("",), ("ftp://127.0.0.1/",), ("http://",),
                              ("http:///ready",), ("--help",), (base + "/has space",),
                              (base + "/ready", "1", "extra")):
                with self.subTest(arguments=arguments):
                    code, _, error, _ = self.run_wait(*arguments)
                    self.assertEqual(code, 2, error)
            self.assertEqual(server.requests, 0)

    def test_stalled_http_response_cannot_exceed_configured_timeout(self):
        with health_server() as (_, base):
            code, _, error, elapsed = self.run_wait(base + "/stall", "1", limit=3)
            self.assertEqual(code, 1, error)
            self.assertIn("服务未就绪", error)
            self.assertLess(elapsed, 2.5)


if __name__ == "__main__":
    unittest.main()
