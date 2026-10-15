"""Exercise the SSH stdin installer with real bash and an isolated Docker CLI."""
import gzip
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
INSTALLER = ROOT / "deploy/remote-install.sh"
REVISION = "b" * 40
OLD_REVISION = "a" * 40

DOCKER = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

args = sys.argv[1:]
def record(**extra):
    with Path(os.environ["FAKE_DOCKER_LOG"]).open("a") as output:
        output.write(json.dumps({"args": args, "commit": os.environ.get("BUILD_COMMIT"), **extra}) + "\n")

if args == ["load"]:
    record(image_bytes=len(sys.stdin.buffer.read()))
    sys.exit(0)
if "up" in args:
    record()
    if os.environ.get("FAKE_UP_FAIL") == "1":
        sys.exit(17)
    if os.environ.get("FAKE_UP_READ_STDIN") == "1":
        record(consumed=len(sys.stdin.read()))
    sys.exit(0)
if "exec" in args:
    if "--interactive=false" not in args:
        record(consumed=len(sys.stdin.read()))
    else:
        record()
    print("1|initial schema|t" if "psql" in args else '{"status":"UP"}')
    sys.exit(0)
record()
sys.exit(2)
'''


class RemoteInstallTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.home = Path(temporary.name) / "remote home"
        self.home.mkdir()
        self.deploy_root = self.home / "campus releases"
        self.environment_file = self.home / "deployment.env"
        self.environment_file.write_text("BUILD_COMMIT=local\n")
        incoming = self.home / "incoming"
        incoming.mkdir()
        with tarfile.open(incoming / ("source-" + REVISION + ".tar.gz"), "w:gz") as archive:
            data = b"services: {}\n"
            entry = tarfile.TarInfo("deploy/compose.yml")
            entry.size = len(data)
            archive.addfile(entry, io.BytesIO(data))
        with gzip.open(incoming / ("images-" + REVISION + ".tar.gz"), "wb") as archive:
            archive.write(b"verified image archive")
        binaries = self.home / "bin"
        binaries.mkdir()
        docker = binaries / "docker"
        docker.write_text(DOCKER)
        docker.chmod(0o755)
        if shutil.which("flock") is None:
            # macOS lacks the CLI; this adapter still takes a real OS file lock.
            flock = binaries / "flock"
            flock.write_text("#!/usr/bin/env python3\nimport fcntl,sys\nfcntl.flock(int(sys.argv[-1]), fcntl.LOCK_EX)\n")
            flock.chmod(0o755)
        self.log = self.home / "docker.jsonl"
        self.environment = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ["PATH"],
                                FAKE_DOCKER_LOG=str(self.log))
        self.current = self.deploy_root / "campus-test.current"
        self.previous = self.deploy_root / "campus-test.previous"
        self.migrations = self.deploy_root / "releases" / REVISION / "migrations-campus-test.txt"

    def install(self, **environment):
        return subprocess.run(["bash", "-s", "--", str(self.deploy_root), REVISION,
                               str(self.environment_file), "campus-test"],
                              input=INSTALLER.read_text(), cwd=self.home,
                              env=dict(self.environment, **environment), capture_output=True,
                              text=True, timeout=10)

    def test_ssh_stdin_installer_records_release_after_all_health_checks(self):
        self.deploy_root.mkdir()
        self.current.write_text(OLD_REVISION + "\n")
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.current.read_text(), REVISION + "\n")
        self.assertEqual(self.previous.read_text(), OLD_REVISION + "\n")
        self.assertEqual(self.migrations.read_text(), "1|initial schema|t\n")
        commands = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertEqual(sum("exec" in command["args"] for command in commands), 3)
        self.assertTrue(all(command["commit"] == REVISION
                            for command in commands if "compose" in command["args"]))

    def test_detached_up_cannot_consume_the_ssh_script_input(self):
        result = self.install(FAKE_UP_READ_STDIN="1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(self.current.is_file(), "up consumed the remaining installer")
        self.assertEqual(self.current.read_text(), REVISION + "\n")
        self.assertEqual(self.migrations.read_text(), "1|initial schema|t\n")

    def test_first_install_writes_current_and_migrations_without_previous(self):
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.current.read_text(), REVISION + "\n")
        self.assertFalse(self.previous.exists())
        self.assertEqual(self.migrations.read_text(), "1|initial schema|t\n")

    def test_failed_up_does_not_update_any_release_record(self):
        self.deploy_root.mkdir()
        self.current.write_text(OLD_REVISION + "\n")
        self.previous.write_text("c" * 40 + "\n")
        result = self.install(FAKE_UP_FAIL="1")
        self.assertEqual(result.returncode, 17, result.stderr)
        self.assertEqual(self.current.read_text(), OLD_REVISION + "\n")
        self.assertEqual(self.previous.read_text(), "c" * 40 + "\n")
        self.assertFalse(self.migrations.exists())
        commands = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertFalse(any("exec" in command["args"] for command in commands))


if __name__ == "__main__":
    unittest.main()
