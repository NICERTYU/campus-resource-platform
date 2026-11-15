#!/usr/bin/env bash
# SSH stdin script. Deploys the exact images tested in CI; no remote compilation.
set -euo pipefail
deploy_root="${1:?}"; revision="${2:?}"; env_file="${3:?}"; project="${4:?}"
[[ "$deploy_root" == /* && "$env_file" == /* && "$revision" =~ ^[0-9a-f]{40}$ ]] || exit 2
[[ "$project" == campus-test || "$project" == campus-prod ]] || exit 2
phase='acquire deployment lock'
trap 'result=$?; printf "[deploy] FAILED: %s (exit %s)\n" "$phase" "$result" >&2; exit "$result"' ERR
echo '[deploy] Waiting for deployment lock (limit 180s)'
mkdir -p "$deploy_root/releases" "$deploy_root/locks"
exec 9>"$deploy_root/locks/$project.lock"
flock -w 180 9
release_dir="$deploy_root/releases/$revision"
mkdir -p "$release_dir"
phase='extract verified source'
echo '[deploy] Extracting verified source (limit 30s)'
timeout --signal=TERM --kill-after=10s 30s tar -xzf "incoming/source-$revision.tar.gz" -C "$release_dir" </dev/null
phase='load verified Docker images'
echo '[deploy] Loading verified Docker images (limit 300s)'
timeout --signal=TERM --kill-after=10s 300s bash -o pipefail -c 'gzip -dc "$1" | docker load' _ "incoming/images-$revision.tar.gz" </dev/null
export BUILD_COMMIT="$revision"
compose=(docker compose --env-file "$env_file" -p "$project" -f "$release_dir/deploy/compose.yml")
# Keep the SSH-fed script stdin away from Compose commands.
phase='start services and wait for health'
echo '[deploy] Starting services and waiting for health (limit 210s)'
timeout --signal=TERM --kill-after=10s 210s "${compose[@]}" up -d --no-build --wait --wait-timeout 180 </dev/null
phase='resource application health'
echo '[deploy] Checking resource application health'
timeout --signal=TERM --kill-after=10s 25s "${compose[@]}" exec -T --interactive=false resource curl -fsS --connect-timeout 5 --max-time 15 http://localhost:8081/actuator/health </dev/null
phase='workflow application health'
echo '[deploy] Checking workflow application health'
timeout --signal=TERM --kill-after=10s 25s "${compose[@]}" exec -T --interactive=false workflow curl -fsS --connect-timeout 5 --max-time 15 http://localhost:8082/actuator/health </dev/null
phase='verify database migration history'
echo '[deploy] Verifying database migration history'
timeout --signal=TERM --kill-after=10s 30s "${compose[@]}" exec -T --interactive=false db \
  env PGCONNECT_TIMEOUT=10 PGOPTIONS='-c statement_timeout=15000' psql -v ON_ERROR_STOP=1 -U campus_owner -d campus -c \
  'SELECT version,description,success FROM resource.flyway_schema_history UNION ALL SELECT version,description,success FROM workflow.flyway_schema_history' \
  > "$release_dir/migrations-$project.txt.tmp" </dev/null
mv "$release_dir/migrations-$project.txt.tmp" "$release_dir/migrations-$project.txt"
phase='record completed release'
previous_file="$deploy_root/$project.current"
if [[ -f "$previous_file" ]]; then cp "$previous_file" "$deploy_root/$project.previous"; fi
printf '%s\n' "$revision" > "$previous_file.tmp"
mv "$previous_file.tmp" "$previous_file"
echo "Deployed $project $revision"
