#!/usr/bin/env bash
# Runs on the deployment host after the runner has checked the public endpoint.
set -euo pipefail
deploy_root="${1:?}"; project="${2:?}"; revision="${3:?}"
[[ "$deploy_root" == /* && "$revision" =~ ^[0-9a-f]{40}$ ]] || exit 2
[[ "$project" == campus-test || "$project" == campus-prod ]] || exit 2
exec 9>"$deploy_root/locks/release-images.lock"
flock -w 180 9
if [[ ! -f "$deploy_root/$project.current" || "$(<"$deploy_root/$project.current")" != "$revision" ]]; then
  echo '[deploy] Installed version changed during public verification; skipping confirmation and cleanup' >&2
  exit 1
fi

verified_file="$deploy_root/$project.verified"
printf '%s\n' "$revision" > "$verified_file.tmp"
mv "$verified_file.tmp" "$verified_file"
printf '[deploy] Public endpoint verified for %s %s\n' "$project" "$revision"

if ! python3 - "$deploy_root" <<'PYTHON'
from pathlib import Path
import re
import subprocess
import sys

root = Path(sys.argv[1])
environments = ("campus-test", "campus-prod")
repositories = {"campus-resource", "campus-workflow", "campus-web"}
keep_releases = 5
revision_pattern = re.compile(r"[0-9a-f]{40}")


def docker_lines(*args):
    return subprocess.check_output(["docker", *args], text=True).splitlines()


protected = set()
for environment in environments:
    for marker in ("current", "previous", "verified"):
        path = root / f"{environment}.{marker}"
        if path.is_file():
            revision = path.read_text().strip()
            if not revision_pattern.fullmatch(revision):
                sys.exit(f"[deploy] Invalid release marker {path}; skipping image cleanup")
            protected.add(revision)

containers = docker_lines("ps", "-aq")
used_ids = set(docker_lines("container", "inspect", "--format", "{{.Image}}", *containers)) if containers else set()

# Group only exact commit tags with a successful installation record on this host.
images = {}
installed_at = {}
for row in docker_lines("image", "ls", "--no-trunc", "--format", "{{.Repository}} {{.Tag}} {{.ID}}"):
    repository, revision, image_id = row.split()
    if repository not in repositories or not revision_pattern.fullmatch(revision):
        continue
    records = [root / "releases" / revision / f"migrations-{env}.txt" for env in environments]
    times = [record.stat().st_mtime_ns for record in records if record.is_file()]
    if not times:
        continue
    installed_at[revision] = max(times)
    images.setdefault(revision, []).append(f"{repository}:{revision}")
    if image_id in used_ids:
        protected.add(revision)

retained = protected.intersection(images)
for revision in sorted(images, key=lambda item: (installed_at[item], item), reverse=True):
    if len(retained) >= keep_releases:
        break
    retained.add(revision)
print(f"[deploy] Retaining {len(retained)} managed release versions (target: {keep_releases})", flush=True)

failed = False
for revision, tags in images.items():
    if revision in retained:
        continue
    for tag in tags:
        if subprocess.run(["docker", "image", "rm", tag], check=False).returncode:
            print(f"[deploy] Could not remove old image {tag}", file=sys.stderr)
            failed = True
sys.exit(1 if failed else 0)
PYTHON
then
  echo '::warning::Some old images could not be cleaned. Check the deployment log.' >&2
fi
