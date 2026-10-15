"""Keep the public platform repository free of local data and unrelated tools.

Only Git paths and ignore decisions are inspected; file contents are never read.
"""
from __future__ import annotations

from pathlib import Path, PurePosixPath
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
UNRELATED_DIRECTORIES = ("output/resume", "tools/resume", "tools/clash")


def forbidden_reason(path: str) -> str | None:
    """Classify sensitive or unrelated paths without examining their contents."""
    parts = PurePosixPath(path).parts
    if any(path == directory or path.startswith(directory + "/")
           for directory in UNRELATED_DIRECTORIES):
        return "unrelated personal files or local proxy tools"
    if ".local" in parts:
        return "local runtime data"
    name = PurePosixPath(path).name
    if name.endswith(".env.example"):
        return None
    if name == ".env" or name.startswith(".env.") or name.endswith(".env"):
        return "non-example environment configuration"
    return None


class RepositoryScopeTests(unittest.TestCase):
    def test_git_index_contains_only_publishable_paths(self):
        result = subprocess.run(
            ["git", "ls-files", "--cached", "-z"], cwd=ROOT,
            check=True, capture_output=True, text=True,
        )
        paths = [path for path in result.stdout.split("\0") if path]
        self.assertTrue(paths, "The Git index must contain project files")
        forbidden = {path: reason for path in paths
                     if (reason := forbidden_reason(path)) is not None}
        self.assertEqual(forbidden, {}, "Remove these paths from the Git index")

    def test_scope_guard_rejects_unrelated_paths_and_secret_variants(self):
        for path in (
            "output/resume/document.pdf", "tools/resume/build.py",
            "tools/clash/settings.json", ".local/session.json",
            "backend/.local/cache.json", ".env", ".env.production",
            "deploy/staging.env", "frontend/.env.local",
        ):
            with self.subTest(path=path):
                self.assertIsNotNone(forbidden_reason(path))

    def test_scope_guard_allows_example_configuration_and_product_files(self):
        for path in (
            "deploy/.env.example", "frontend/.env.example",
            "deploy/staging.env.example", "frontend/src/env.d.ts",
            "tools/check-platform.py", "output/architecture.svg",
            "tools/resume-validator.py",
        ):
            with self.subTest(path=path):
                self.assertIsNone(forbidden_reason(path))

    def test_unrelated_and_private_paths_are_ignored(self):
        for path in (
            "output/resume/document.pdf", "output/resume/nested/document.docx",
            "tools/resume/build.py", "tools/clash/settings.json",
            ".local/session.json", "backend/.local/cache.json",
            ".env", ".env.production", "deploy/staging.env",
            "frontend/.env.local",
        ):
            with self.subTest(path=path):
                result = subprocess.run(
                    ["git", "check-ignore", "--no-index", "--quiet", "--", path],
                    cwd=ROOT, capture_output=True, text=True,
                )
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_example_configuration_and_product_sources_are_not_ignored(self):
        for path in (
            "deploy/.env.example", "frontend/.env.example",
            "deploy/staging.env.example", "frontend/src/env.d.ts",
            "tools/check-platform.py", "output/architecture.svg",
            "tools/resume-validator.py",
        ):
            with self.subTest(path=path):
                result = subprocess.run(
                    ["git", "check-ignore", "--no-index", "--quiet", "--", path],
                    cwd=ROOT, capture_output=True, text=True,
                )
                self.assertEqual(result.returncode, 1, result.stderr)


if __name__ == "__main__":
    unittest.main()
