"""Exercise release transport failures without contacting deployment hosts."""
import json
import os
from pathlib import Path
import shlex
import shutil
import socket
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
REVISION = "b" * 40
CLI = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys
import time

name = Path(sys.argv[0]).name
args = sys.argv[1:]
key = Path(args[args.index("-i") + 1])
entry = {"name": name, "args": args, "key_mode": key.stat().st_mode & 0o777}
if name == "ssh":
    entry["stdin"] = sys.stdin.read()
with Path(os.environ["FAKE_CLI_LOG"]).open("a") as output:
    output.write(json.dumps(entry) + "\n")
if name == "ssh" and args[-1] == "mkdir -p incoming" and os.environ.get("FAKE_CONNECT_FAIL"):
    sys.exit(255)
if name == "scp" and os.environ.get("FAKE_UPLOAD_STALL"):
    time.sleep(30)
if name == "scp" and os.environ.get("FAKE_UPLOAD_DELAY"):
    time.sleep(16)
if name == "ssh" and args[-1].startswith("bash -s --") and os.environ.get("FAKE_INSTALL_FAIL"):
    sys.exit(17)
'''


class ReleaseTransportTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / "release source"
        (self.root / "scripts").mkdir(parents=True)
        (self.root / "deploy").mkdir()
        self.runner_temp = self.root / "runner temporary"
        self.runner_temp.mkdir()
        shutil.copyfile(ROOT / "scripts/install-verified-release.sh",
                        self.root / "scripts/install-verified-release.sh")
        shutil.copyfile(ROOT / "deploy/remote-install.sh", self.root / "deploy/remote-install.sh")
        self.artifacts = self.root / ".local/artifact"
        self.artifacts.mkdir(parents=True)
        for name in ("source", "images"):
            (self.artifacts / f"{name}-{REVISION}.tar.gz").write_bytes(b"verified artifact")
        # An unrelated artifact must never be included in the upload.
        (self.artifacts / "unrelated-secret.txt").write_text("not part of release")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for name in ("ssh", "scp"):
            cli = self.bin / name
            cli.write_text(CLI)
            cli.chmod(0o755)
        self.log = self.root / "transport.jsonl"
        self.environment = dict(
            os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
            FAKE_CLI_LOG=str(self.log), RUNNER_TEMP=str(self.runner_temp),
            SSH_HOST="example.invalid", SSH_USER="deployer", SSH_PORT="2222",
            SSH_KEY="private-key-test-sentinel", SSH_KNOWN_HOSTS="known-host-test-sentinel",
            DEPLOY_ROOT="/srv/campus releases", DEPLOY_ENV_FILE="/srv/private/test env",
            GITHUB_SHA=REVISION,
        )

    def install(self, project="campus-test", **environment):
        return subprocess.run(
            ["bash", "scripts/install-verified-release.sh", project], cwd=self.root,
            env=dict(self.environment, **environment), capture_output=True, text=True,
            timeout=22 if environment.get("FAKE_UPLOAD_DELAY") else 10,
        )

    def commands(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def assert_private_files_cleaned(self, result):
        self.assertEqual(list(self.runner_temp.iterdir()), [])
        self.assertNotIn(self.environment["SSH_KEY"], result.stdout + result.stderr)
        self.assertNotIn(self.environment["SSH_KNOWN_HOSTS"], result.stdout + result.stderr)

    def test_success_uploads_only_exact_revision_with_strict_ssh_and_script_stdin(self):
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        commands = self.commands()
        self.assertEqual([command["name"] for command in commands], ["ssh", "scp", "ssh"])
        for command in commands:
            self.assertEqual(command["key_mode"], 0o600)
            for option in ("BatchMode=yes", "StrictHostKeyChecking=yes", "ConnectTimeout=15",
                           "ServerAliveInterval=15", "ServerAliveCountMax=3"):
                self.assertIn(option, command["args"])
        self.assertEqual(commands[0]["stdin"], "")
        self.assertEqual(commands[2]["stdin"], (self.root / "deploy/remote-install.sh").read_text())
        self.assertEqual(shlex.split(commands[2]["args"][-1]), [
            "bash", "-s", "--", "/srv/campus releases", REVISION, "/srv/private/test env", "campus-test",
        ])
        upload = commands[1]["args"]
        self.assertIn(f".local/artifact/images-{REVISION}.tar.gz", upload)
        self.assertIn(f".local/artifact/source-{REVISION}.tar.gz", upload)
        self.assertFalse(any("unrelated-secret" in argument for argument in upload))
        self.assertIn("DONE: upload verified source and images", result.stdout)
        self.assert_private_files_cleaned(result)

    def test_ssh_failure_stops_before_upload_and_cleans_private_files(self):
        result = self.install(FAKE_CONNECT_FAIL="1")
        self.assertEqual(result.returncode, 255, result.stderr)
        self.assertEqual(len(self.commands()), 1)
        self.assertIn("FAILED: SSH connection", result.stderr)
        self.assert_private_files_cleaned(result)

    def test_real_upload_timeout_cannot_start_remote_installation(self):
        result = self.install(FAKE_UPLOAD_STALL="1", DEPLOY_UPLOAD_TIMEOUT_SECONDS="1")
        self.assertEqual(result.returncode, 124, result.stderr)
        self.assertEqual([command["name"] for command in self.commands()], ["ssh", "scp"])
        self.assertIn("TIMEOUT: upload verified source and images", result.stderr)
        self.assert_private_files_cleaned(result)

    def test_slow_upload_emits_a_heartbeat_and_can_still_finish(self):
        result = self.install(FAKE_UPLOAD_DELAY="1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("WAIT: upload verified source and images", result.stdout)
        self.assertIn("DONE: upload verified source and images", result.stdout)
        self.assert_private_files_cleaned(result)

    def test_remote_installation_failure_is_preserved_and_not_marked_complete(self):
        result = self.install(FAKE_INSTALL_FAIL="1")
        self.assertEqual(result.returncode, 17, result.stderr)
        self.assertIn("FAILED: remote image installation", result.stderr)
        self.assertNotIn("Verified release installation completed", result.stdout)
        self.assert_private_files_cleaned(result)

    def test_missing_archive_fails_before_connecting(self):
        (self.artifacts / f"images-{REVISION}.tar.gz").unlink()
        result = self.install()
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertEqual(self.commands(), [])
        self.assertIn("Missing verified release archives", result.stderr)

    def test_production_uses_the_same_transport_with_distinct_environment(self):
        result = self.install(project="campus-prod")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(shlex.split(self.commands()[-1]["args"][-1])[-1], "campus-prod")
        self.assert_private_files_cleaned(result)

    def test_real_ssh_connection_refused_fails_before_upload(self):
        real_ssh = shutil.which("ssh")
        self.assertIsNotNone(real_ssh)
        cli = self.bin / "ssh"
        cli.write_text("#!/usr/bin/env python3\nimport os,sys\nos.execv(" + repr(real_ssh) +
                       ", ['ssh', *sys.argv[1:]])\n")
        # A bound, non-listening socket reserves a local port that refuses TCP.
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            result = self.install(SSH_HOST="127.0.0.1", SSH_PORT=str(listener.getsockname()[1]), LC_ALL="C")
        self.assertEqual(result.returncode, 255, result.stderr)
        self.assertIn("Connection refused", result.stderr)
        self.assertEqual(self.commands(), [])
        self.assert_private_files_cleaned(result)


if __name__ == "__main__":
    unittest.main()
