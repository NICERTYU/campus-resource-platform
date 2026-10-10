"""Regression guards for the public boundary and reproducible deployment."""
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]


class DeploymentBoundaryTests(unittest.TestCase):
    def test_public_proxy_denies_internal_commands(self):
        config = (ROOT / "deploy/nginx.conf").read_text()
        self.assertRegex(config, r"location\s+/internal/\s*\{\s*return 404;")
        self.assertRegex(config, r"location\s+/actuator/\s*\{\s*return 404;")
        self.assertIn("proxy_pass http://workflow:8082", config)
        self.assertIn("proxy_pass http://resource:8081", config)

    def test_database_and_module_ports_not_published(self):
        config = (ROOT / "deploy/compose.yml").read_text()
        for service in ("db", "resource", "workflow"):
            match = re.search(r"^  " + service + r":\n(.*?)(?=^  [a-z][\w-]*:|\Z)", config, re.M | re.S)
            self.assertIsNotNone(match)
            self.assertNotIn("ports:", match.group(1), service + " must stay internal")
        self.assertIn("image: postgres:18.6", config)
        self.assertNotIn(":latest", config)

    def test_web_health_uses_explicit_ipv4_listener(self):
        config = (ROOT / "deploy/compose.yml").read_text()
        self.assertIn("[CMD, wget, -q, --spider, http://127.0.0.1/]", config)
        self.assertIn("listen 80", (ROOT / "deploy/nginx.conf").read_text())

    def test_database_owners_cannot_access_each_others_schema(self):
        config = (ROOT / "deploy/init-db.sh").read_text()
        for schema in ("resource", "workflow"):
            self.assertIn("CREATE SCHEMA %s AUTHORIZATION campus_%s" % (schema, schema), config)
            self.assertIn("REVOKE ALL ON SCHEMA %s FROM PUBLIC" % schema, config)
        self.assertNotIn("GRANT ALL", config)

    def test_shell_scripts_parse(self):
        for script in sorted((ROOT / "scripts").glob("*.sh")) + sorted((ROOT / "deploy").glob("*.sh")):
            with self.subTest(script=script.name):
                result = subprocess.run(["bash", "-n", str(script)], capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_release_uses_verified_images_and_distinct_environments(self):
        script = (ROOT / "deploy/remote-install.sh").read_text()
        workflow = (ROOT / ".github/workflows/ci.yml").read_text()
        self.assertIn("--no-build", script)
        self.assertIn("docker load", script)
        self.assertIn("environment: campus-test", workflow)
        self.assertIn("environment: campus-prod", workflow)
        self.assertIn("cancel-in-progress: false", workflow)
        self.assertIn("Skip superseded commit", workflow)
        self.assertEqual(workflow.count("printf -v remote_cmd"), 2)
        self.assertIn("flock -w 180", (ROOT / "scripts/rollback.sh").read_text())
        self.assertIn("workflow-smoke.json", workflow)
        self.assertIn("/api/v1/auth/csrf", workflow)
        self.assertIn("frontend_pid=$!", (ROOT / "scripts/dev.sh").read_text())
        self.assertIn("trap stop_on_signal INT TERM", (ROOT / "scripts/dev.sh").read_text())


if __name__ == "__main__":
    unittest.main()
