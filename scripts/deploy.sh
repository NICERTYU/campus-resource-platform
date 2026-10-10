#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
environment_file="${1:?Usage: scripts/deploy.sh ENV_FILE PROJECT COMMIT}"
project="${2:?set isolated project campus-test or campus-prod}"
revision="${3:?set verified Git commit}"
[[ "$project" == campus-test || "$project" == campus-prod ]] || { echo '仅接受campus-test或campus-prod'; exit 1; }
[[ "$(git rev-parse HEAD)" == "$revision" ]] || { echo '工作目录必须处于指定提交'; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo '工作目录必须干净'; exit 1; }
mkdir -p .local/releases
exec 9>".local/releases/$project.lock"
flock -n 9 || { echo '该环境正在发布'; exit 1; }
export BUILD_COMMIT="$revision"
compose=(docker compose --env-file "$environment_file" -p "$project" -f deploy/compose.yml)
"${compose[@]}" build --pull
"${compose[@]}" up -d --wait --wait-timeout 180
"${compose[@]}" ps
"${compose[@]}" exec -T resource curl -fsS http://localhost:8081/actuator/health
"${compose[@]}" exec -T workflow curl -fsS http://localhost:8082/actuator/health
python3 - "$project" "$revision" <<'PY' > ".local/releases/$project.json"
import json,sys,datetime
print(json.dumps({'environment':sys.argv[1], 'commit':sys.argv[2], 'validatedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}, indent=2))
PY
echo "已部署并通过健康检查：$project $revision。业务冒烟测试仍由流水线执行。"
