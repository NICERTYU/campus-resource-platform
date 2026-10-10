#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/init-local-env.sh
set -a
source .local/dev.env
set +a
if [[ "$(uname)" == Darwin ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
docker compose --env-file .local/dev.env -p campus-dev -f deploy/compose.yml -f deploy/compose.dev.yml up -d --wait db
(cd backend && ./mvnw -B -DskipTests package)
export MODULE_KEY BUILD_COMMIT
export DB_URL="jdbc:postgresql://127.0.0.1:${DB_PORT}/campus?currentSchema=resource" DB_USERNAME=campus_resource DB_PASSWORD="$RESOURCE_DB_PASSWORD"
clock_args=()
if [[ -n "${E2E_CLOCK:-}" ]]; then clock_args=(--spring.profiles.active=e2e "--campus.test-clock=$E2E_CLOCK"); fi
java -jar backend/resource-app/target/resource-app-0.1.0.jar --campus.seed=true --server.port=0 "${clock_args[@]}"
java -jar backend/resource-app/target/resource-app-0.1.0.jar "${clock_args[@]}" > .local/resource.log 2>&1 &
resource_pid=$!
export DB_URL="jdbc:postgresql://127.0.0.1:${DB_PORT}/campus?currentSchema=workflow" DB_USERNAME=campus_workflow DB_PASSWORD="$WORKFLOW_DB_PASSWORD" RESOURCE_URL=http://127.0.0.1:8081
java -jar backend/workflow-app/target/workflow-app-0.1.0.jar > .local/workflow.log 2>&1 &
workflow_pid=$!
frontend_pid=''
cleanup() {
  if [[ -n "$frontend_pid" ]]; then
    # npm is a launcher; terminate its Vite children before terminating npm.
    pkill -TERM -P "$frontend_pid" 2>/dev/null || true
    kill "$frontend_pid" 2>/dev/null || true
  fi
  kill "$resource_pid" "$workflow_pid" 2>/dev/null || true
}
stop_on_signal() { cleanup; exit 143; }
trap cleanup EXIT
trap stop_on_signal INT TERM
./scripts/wait-health.sh http://127.0.0.1:8081/actuator/health
./scripts/wait-health.sh http://127.0.0.1:8082/actuator/health
echo '校园资源平台：http://localhost:5173；后端日志：.local/；Ctrl+C停止应用，数据库数据保留。'
cd frontend
npm ci
npm run dev -- --host 127.0.0.1 &
frontend_pid=$!
wait "$frontend_pid"
