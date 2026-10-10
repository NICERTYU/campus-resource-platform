#!/usr/bin/env bash
set -euo pipefail
deploy_root="${1:?Usage: rollback.sh DEPLOY_ROOT ENV_FILE PROJECT PREVIOUS_COMMIT}"
env_file="${2:?}"; project="${3:?}"; revision="${4:?}"
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] || exit 2
[[ "$project" == campus-test || "$project" == campus-prod ]] || exit 2
echo '仅回退应用制品。运行前按部署文档确认数据库迁移与旧应用兼容，破坏性变更须从备份恢复。'
mkdir -p "$deploy_root/locks"
exec 9>"$deploy_root/locks/$project.lock"
flock -w 180 9
export BUILD_COMMIT="$revision"
docker compose --env-file "$env_file" -p "$project" -f "$deploy_root/releases/$revision/deploy/compose.yml" up -d --no-build --wait --wait-timeout 180
printf '%s\n' "$revision" > "$deploy_root/$project.current"
