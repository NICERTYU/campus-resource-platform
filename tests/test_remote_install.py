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
CONFIRM = ROOT / "deploy/confirm-release.sh"
REVISION = "b" * 40
OLD_REVISION = "a" * 40

DOCKER = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys
import time

args = sys.argv[1:]
def record(**extra):
    with Path(os.environ["FAKE_DOCKER_LOG"]).open("a") as output:
        output.write(json.dumps({"args": args, "commit": os.environ.get("BUILD_COMMIT"), **extra}) + "\n")

if args == ["load"]:
    if os.environ.get("FAKE_LOAD_STALL") == "1":
        time.sleep(30)
    record(image_bytes=len(sys.stdin.buffer.read()))
    sys.exit(0)
if args == ["ps", "-aq"]:
    print(os.environ.get("FAKE_CONTAINERS", ""))
    sys.exit(0)
if args[:2] == ["container", "inspect"]:
    print(os.environ["FAKE_CONTAINER_IMAGE"])
    sys.exit(0)
if args[:2] == ["image", "ls"]:
    print(os.environ.get("FAKE_IMAGES", ""))
    sys.exit(0)
if args[:2] == ["image", "rm"]:
    record()
    sys.exit(0)
if "up" in args:
    record()
    if os.environ.get("FAKE_UP_FAIL") == "1":
        sys.exit(17)
    if os.environ.get("FAKE_UP_READ_STDIN") == "1":
        record(consumed=len(sys.stdin.read()))
    sys.exit(0)
if "exec" in args:
    if "psql" in args and os.environ.get("FAKE_MIGRATIONS_FAIL") == "1":
        sys.exit(19)
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

    def test_reinstalling_same_revision_preserves_previous_distinct_release(self):
        self.deploy_root.mkdir()
        self.current.write_text(OLD_REVISION + "\n")
        for _ in range(2):
            result = self.install()
            self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.current.read_text(), REVISION + "\n")
        self.assertEqual(self.previous.read_text(), OLD_REVISION + "\n")

        target = self.deploy_root / "releases" / OLD_REVISION / "deploy/compose.yml"
        target.parent.mkdir(parents=True)
        target.write_text("services: {}\n")
        result = subprocess.run(["bash", str(ROOT / "scripts/rollback.sh"), str(self.deploy_root),
                                 str(self.environment_file), "campus-test", OLD_REVISION],
                                cwd=self.home, env=self.environment, capture_output=True,
                                text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.current.read_text(), OLD_REVISION + "\n")
        self.assertEqual(self.previous.read_text(), REVISION + "\n")
        rollback = json.loads(self.log.read_text().splitlines()[-1])
        self.assertEqual(rollback["commit"], OLD_REVISION)
        self.assertIn(str(target), rollback["args"])
        self.assertIn(["--pull", "never"], [rollback["args"][i:i + 2]
                                           for i in range(len(rollback["args"]) - 1)])

    def test_install_after_failed_public_check_prefers_last_verified_rollback_version(self):
        verified = "c" * 40
        self.deploy_root.mkdir()
        self.current.write_text(OLD_REVISION + "\n")
        (self.deploy_root / "campus-test.verified").write_text(verified + "\n")
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.current.read_text(), REVISION + "\n")
        self.assertEqual(self.previous.read_text(), verified + "\n")

    def confirm(self, revision=REVISION, **environment):
        return subprocess.run(["bash", str(CONFIRM), str(self.deploy_root), "campus-test", revision],
                              cwd=self.home, env=dict(self.environment, **environment),
                              capture_output=True, text=True, timeout=10)

    def test_confirmation_retains_five_versions_prioritizing_rollback_and_in_use_images(self):
        self.deploy_root.mkdir()
        self.current.write_text(OLD_REVISION + "\n")
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        production, old, used, unmanaged, newest, middle = (char * 40 for char in "cdef01")
        (self.deploy_root / "campus-prod.current").write_text(production + "\n")
        for order, revision in enumerate((production, OLD_REVISION, used, old, middle, newest), start=1):
            marker = self.migrations.parent.parent / revision / self.migrations.name
            marker.parent.mkdir()
            marker.touch()
            os.utime(marker, (order, order))
        images = [f"{repo} {old} sha256:old-{repo}" for repo in (
            "campus-workflow", "campus-resource", "campus-web")]
        images += [f"campus-workflow {revision} sha256:{revision}" for revision in (
            REVISION, OLD_REVISION, production, unmanaged, newest, middle)]
        images += [f"campus-workflow {used} sha256:used", "campus-workflow local sha256:local",
                   f"unrelated-app {old} sha256:outside"]

        result = self.confirm(FAKE_IMAGES="\n".join(images), FAKE_CONTAINERS="stopped-container",
                              FAKE_CONTAINER_IMAGE="sha256:used")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Retaining 5 managed release versions", result.stdout)
        self.assertEqual((self.deploy_root / "campus-test.verified").read_text(), REVISION + "\n")
        commands = [json.loads(line)["args"] for line in self.log.read_text().splitlines()]
        removed = [command[2:] for command in commands if command[:2] == ["image", "rm"]]
        self.assertEqual(removed, [[f"{repo}:{old}"] for repo in (
            "campus-workflow", "campus-resource", "campus-web")] + [[f"campus-workflow:{middle}"]])

    def test_outdated_confirmation_cannot_mark_release_verified_or_prune_images(self):
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        previous_log = self.log.read_text()
        result = self.confirm(OLD_REVISION)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Installed version changed", result.stderr)
        self.assertFalse((self.deploy_root / "campus-test.verified").exists())
        self.assertEqual(self.log.read_text(), previous_log)

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

    def test_failed_migration_query_keeps_previous_release_records(self):
        self.deploy_root.mkdir()
        self.current.write_text(OLD_REVISION + "\n")
        self.previous.write_text("c" * 40 + "\n")
        result = self.install(FAKE_MIGRATIONS_FAIL="1")
        self.assertEqual(result.returncode, 19, result.stderr)
        self.assertIn("FAILED: verify database migration history", result.stderr)
        self.assertEqual(self.current.read_text(), OLD_REVISION + "\n")
        self.assertEqual(self.previous.read_text(), "c" * 40 + "\n")
        self.assertFalse(self.migrations.exists())

    def test_stalled_image_load_times_out_without_recording_success(self):
        real_timeout = shutil.which("timeout")
        self.assertIsNotNone(real_timeout, "Linux release tests require GNU timeout")
        # Keep the production image-load budget; shorten it only in the test CLI.
        timeout = self.home / "bin" / "timeout"
        timeout.write_text(
            "#!/usr/bin/env python3\nimport os,sys\n"
            "args = ['1s' if value == '300s' else value for value in sys.argv[1:]]\n"
            "os.execv(" + repr(real_timeout) + ", ['timeout', *args])\n"
        )
        timeout.chmod(0o755)
        result = self.install(FAKE_LOAD_STALL="1")
        self.assertEqual(result.returncode, 124, result.stderr)
        self.assertIn("FAILED: load verified Docker images", result.stderr)
        self.assertFalse(self.current.exists())
        self.assertFalse(self.migrations.exists())


if __name__ == "__main__":
    unittest.main()
