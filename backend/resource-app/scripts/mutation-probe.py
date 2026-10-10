#!/usr/bin/env python3
"""Prove the source-retention regression test detects an incorrect global release."""
from pathlib import Path
import os
import re
import subprocess

root = Path(__file__).resolve().parents[3]
source = root / "backend/resource-app/src/main/java/cn/campus/resource/ResourceService.java"
original = source.read_text()
anchor = re.compile(r'(?m)^[ \t]*audit\(\s*actor\.id\(\),\s*input\.active\(\)\s*\?\s*"RESTRICTION_ESTABLISHED"\s*:\s*"RESTRICTION_RELEASED",\s*type,\s*sourceId,\s*input\.requestId\(\)\);')
matches = list(anchor.finditer(original))
assert len(matches) == 1, "Mutation anchor must be unique"
match = matches[0]
mutation = '      if (!input.active()) { db.update("UPDATE restrictions SET active=false WHERE classroom_id=?", input.classroomId()); }\n'
mutated = original[:match.start()] + mutation + original[match.start():]
command = [str(root / "backend/mvnw"), "-q", "-f", str(root / "backend/resource-app/pom.xml"),
           "-Dtest=ResourceIntegrationTest#sourceRetentionTombstonesAndParameterConflictsAreEnforced", "test"]
output = root / "backend/resource-app/target"
output.mkdir(exist_ok=True)
try:
    source.write_text(mutated)
    broken = subprocess.run(command, cwd=root, env=os.environ.copy(), text=True, capture_output=True)
    evidence = broken.stdout + broken.stderr
    (output / "mutation-broken.log").write_text(evidence)
    assert broken.returncode != 0 and "expected: <[M3]> but was: <[]>" in evidence, \
        "Expected source-retention assertion did not fail; inspect target/mutation-broken.log"
finally:
    source.write_text(original)
restored = subprocess.run(command, cwd=root, env=os.environ.copy(), text=True, capture_output=True)
(output / "mutation-restored.log").write_text(restored.stdout + restored.stderr)
assert restored.returncode == 0, "Restored implementation must pass; inspect target/mutation-restored.log"
print("PASS: incorrect global release failed source-retention assertion; restored implementation passed.")
