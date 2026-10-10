"""Actual database-account isolation checks on the local Compose PostgreSQL."""
import os
import subprocess
import unittest


@unittest.skipUnless(os.environ.get("CAMPUS_DB_TEST") == "1", "opt in CAMPUS_DB_TEST=1 on Compose database")
class DatabaseAccountBoundary(unittest.TestCase):
    def query(self, owner, sql):
        # Expand credentials inside the container, never in shell text or output.
        variable = "RESOURCE_DB_PASSWORD" if owner == "resource" else "WORKFLOW_DB_PASSWORD"
        script = 'export PGPASSWORD="$%s"; exec psql -h 127.0.0.1 -v ON_ERROR_STOP=1 -U campus_%s -d campus -tAc "$1"' % (variable, owner)
        return subprocess.run(["docker", "exec", os.environ.get("CAMPUS_DB_CONTAINER", "campus-dev-db-1"),
                               "bash", "-c", script, "boundary-test", sql], capture_output=True, text=True)
    def test_resource_can_access_own_tables_and_is_not_superuser(self):
        result = self.query("resource", "SELECT count(*) FROM resource.users")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertGreaterEqual(int(result.stdout.strip()), 6)
        role = self.query("resource", "SELECT rolsuper FROM pg_roles WHERE rolname=current_user")
        self.assertEqual(role.stdout.strip(), "f")
    def test_workflow_can_access_own_tables_and_is_not_superuser(self):
        result = self.query("workflow", "SELECT count(*) FROM workflow.repair_orders")
        self.assertEqual(result.returncode, 0, result.stderr)
        role = self.query("workflow", "SELECT rolsuper FROM pg_roles WHERE rolname=current_user")
        self.assertEqual(role.stdout.strip(), "f")
    def test_resource_cannot_read_workflow_business_tables(self):
        result = self.query("resource", "SELECT count(*) FROM workflow.repair_orders")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("permission denied", result.stderr)
    def test_workflow_cannot_read_resource_identity_tables(self):
        result = self.query("workflow", "SELECT count(*) FROM resource.users")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("permission denied", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
