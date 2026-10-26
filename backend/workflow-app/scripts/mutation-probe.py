#!/usr/bin/env python3
"""Remove reviewer authorization temporarily; prove the rule test fails, then restore."""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[3]
source = root / "backend/workflow-app/src/main/java/cn/campus/workflow/ApprovalPolicy.java"
original = source.read_bytes()
needle = b"configured.isEmpty() || configured.contains(user.id())"
assert original.count(needle) == 1, "Mutation anchor must be unique"
wrapper = "mvnw.cmd" if os.name == "nt" else "mvnw"
command = [str(root / "backend" / wrapper), "-B", "-pl", "workflow-app", "-am",
           "-Dtest=ApprovalPolicyTest", "-Dsurefire.failIfNoSpecifiedTests=false", "test"]
output = root / "backend/workflow-app/target"
output.mkdir(exist_ok=True)

def run(name):
    result = subprocess.run(command, cwd=root / "backend", env=os.environ.copy(),
                            capture_output=True)
    evidence = result.stdout + result.stderr
    (output / name).write_bytes(evidence)
    return result.returncode, evidence

try:
    source.write_bytes(original.replace(needle, b"true", 1))
    code, evidence = run("mutation-broken.log")
    assert code != 0 and b"expected: <false> but was: <true>" in evidence, \
        "Authorization test did not detect the mutation; inspect target/mutation-broken.log"
finally:
    source.write_bytes(original)
code, _ = run("mutation-restored.log")
assert code == 0, "Restored source must pass; inspect target/mutation-restored.log"
print("PASS: missing reviewer authorization failed; restored implementation passed.")
