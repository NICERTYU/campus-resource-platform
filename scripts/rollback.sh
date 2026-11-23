#!/usr/bin/env bash
set -euo pipefail
deploy_root="${1:?Usage: rollback.sh DEPLOY_ROOT ENV_FILE PROJECT PREVIOUS_COMMIT}"
env_file="${2:?}"; project="${3:?}"; revision="${4:?}"
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] || exit 2
[[ "$project" == campus-test || "$project" == campus-prod ]] || exit 2
echo '仅回退应用制品。运行前按部署文档确认数据库迁移与旧应用兼容，破坏性变更须从备份恢复。'
mkdir -p "$deploy_root/locks"
exec 9>"$deploy_root/locks/release-images.lock"
flock -w 180 9
exec 8>"$deploy_root/locks/$project.lock"
flock -w 180 8
export BUILD_COMMIT="$revision"
docker compose --env-file "$env_file" -p "$project" -f "$deploy_root/releases/$revision/deploy/compose.yml" up -d --no-build --pull never --wait --wait-timeout 180
current_file="$deploy_root/$project.current"
if [[ -f "$current_file" && "$(<"$current_file")" != "$revision" ]]; then
  cp "$current_file" "$deploy_root/$project.previous.tmp"
  mv "$deploy_root/$project.previous.tmp" "$deploy_root/$project.previous"
fi
printf '%s\n' "$revision" > "$current_file.tmp"
mv "$current_file.tmp" "$current_file"
