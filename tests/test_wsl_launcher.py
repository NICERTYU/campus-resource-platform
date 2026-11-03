"""Exercise launcher failures and dispatch without installing tools or using Docker."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class WslLauncherTests(unittest.TestCase):
    def test_linux_entry_points_have_unix_line_endings(self):
        scripts = list((ROOT / "scripts").glob("*.sh"))
        scripts += list((ROOT / "deploy").rglob("*.sh"))
        scripts.append(ROOT / "backend/mvnw")
        for script in scripts:
            with self.subTest(script=str(script.relative_to(ROOT))):
                self.assertNotIn(b"\r\n", script.read_bytes())

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name) / "校园 project with spaces"
        (self.repo / "scripts").mkdir(parents=True)
        (self.repo / "backend").mkdir()
        (self.repo / ".node-version").write_text("24.21.0\n")
        shutil.copy(ROOT / "scripts/wsl-dev.sh", self.repo / "scripts/wsl-dev.sh")
        self.bin = self.repo / "fake-bin"
        self.bin.mkdir()
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        HOME=str(self.repo / "home"))
        self.tool("node", 'echo "${FAKE_NODE_MAJOR:-24}"')
        self.tool("javac", "exit 0")
        self.tool("java", 'echo "openjdk version \\\"${FAKE_JAVA_MAJOR:-21}.0.1\\\"" >&2')
        self.tool("docker", 'if [ "$1" = info ]; then exit "${FAKE_DOCKER_EXIT:-0}"; fi')
        self.tool("npm", "echo npm-test")
        self.tool("python3", "echo python-test")
        self.executable(self.repo / "backend/mvnw", "echo maven-test")
        self.executable(self.repo / "scripts/dev.sh",
                        'printf "cwd=%s\\n" "$PWD"; exit 17')

    def executable(self, path, body):
        path.write_text("#!/bin/sh\n" + body + "\n", encoding="utf-8")
        path.chmod(0o755)

    def tool(self, name, body):
        self.executable(self.bin / name, body)

    def run_launcher(self, action):
        return subprocess.run(["bash", str(self.repo / "scripts/wsl-dev.sh"), action],
                              env=self.env, capture_output=True, text=True)

    def test_check_succeeds_with_supported_tools(self):
        result = self.run_launcher("check")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("maven-test", result.stdout)

    def test_rejects_wrong_linux_node(self):
        self.env["FAKE_NODE_MAJOR"] = "22"
        result = self.run_launcher("dev")
        self.assertEqual(result.returncode, 1)
        self.assertIn("Node 24 is required", result.stderr)

    def test_rejects_wrong_java(self):
        self.env["FAKE_JAVA_MAJOR"] = "17"
        result = self.run_launcher("dev")
        self.assertEqual(result.returncode, 1)
        self.assertIn("Java 21 JDK is required", result.stderr)

    def test_reports_docker_permission_failure(self):
        self.env["FAKE_DOCKER_EXIT"] = "1"
        result = self.run_launcher("dev")
        self.assertEqual(result.returncode, 1)
        self.assertIn("Docker is unavailable", result.stderr)

    def test_dispatch_preserves_checkout_path_and_exit_code(self):
        result = self.run_launcher("dev")
        self.assertEqual(result.returncode, 17, result.stderr)
        self.assertIn("cwd=" + str(self.repo), result.stdout)

    def test_unknown_action_fails_before_tool_checks(self):
        result = self.run_launcher("not-an-action")
        self.assertEqual(result.returncode, 2)
        self.assertIn("Unknown action", result.stderr)

    def test_loopback_bypasses_proxy_without_removing_existing_exclusions(self):
        self.env["no_proxy"] = ".example.invalid"
        self.env["NO_PROXY"] = ".internal.invalid"
        self.executable(self.repo / "scripts/dev.sh",
                        'printf "%s\\n%s\\n" "$no_proxy" "$NO_PROXY"')
        result = self.run_launcher("dev")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(".example.invalid,127.0.0.1,localhost,::1", result.stdout)
        self.assertIn(".internal.invalid,127.0.0.1,localhost,::1", result.stdout)


if __name__ == "__main__":
    unittest.main()
