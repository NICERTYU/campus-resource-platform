#!/usr/bin/env bash
# SSH stdin script. Deploys the exact images tested in CI; no remote compilation.
set -euo pipefail
deploy_root="${1:?}"; revision="${2:?}"; env_file="${3:?}"; project="${4:?}"
[[ "$deploy_root" == /* && "$env_file" == /* && "$revision" =~ ^[0-9a-f]{40}$ ]] || exit 2
[[ "$project" == campus-test || "$project" == campus-prod ]] || exit 2
mkdir -p "$deploy_root/releases" "$deploy_root/locks"
exec 9>"$deploy_root/locks/$project.lock"
flock -w 180 9
release_dir="$deploy_root/releases/$revision"
mkdir -p "$release_dir"
tar -xzf "incoming/source-$revision.tar.gz" -C "$release_dir"
gzip -dc "incoming/images-$revision.tar.gz" | docker load
export BUILD_COMMIT="$revision"
compose=(docker compose --env-file "$env_file" -p "$project" -f "$release_dir/deploy/compose.yml")
"${compose[@]}" up -d --no-build --wait --wait-timeout 180
"${compose[@]}" exec -T resource curl -fsS http://localhost:8081/actuator/health
"${compose[@]}" exec -T workflow curl -fsS http://localhost:8082/actuator/health
previous_file="$deploy_root/$project.current"
if [[ -f "$previous_file" ]]; then cp "$previous_file" "$deploy_root/$project.previous"; fi
printf '%s\n' "$revision" > "$previous_file"
"${compose[@]}" exec -T db psql -U campus_owner -d campus -c \
  'SELECT version,description,success FROM resource.flyway_schema_history UNION ALL SELECT version,description,success FROM workflow.flyway_schema_history' \
  > "$deploy_root/releases/$revision/migrations-$project.txt"
echo "Deployed $project $revision"
