#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

action="${1:-dev}"
case "$action" in
  check|dev|test|http|database|browser-install|e2e) ;;
  *) echo "Unknown action: $action" >&2; exit 2 ;;
esac

# Prefer the official, checksum-verified project version when installed locally.
node_version="$(tr -d '\r\n' < .node-version)"
node_bin="$HOME/.local/share/campus-tools/node-v${node_version}-linux-x64/bin"
if [[ -x "$node_bin/node" ]]; then export PATH="$node_bin:$PATH"; fi
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
export PYTHONDONTWRITEBYTECODE=1
# curl does not interpret Windows' 127.* proxy exclusion as a subnet.
export NO_PROXY="${NO_PROXY:+$NO_PROXY,}127.0.0.1,localhost,::1"
export no_proxy="${no_proxy:+$no_proxy,}127.0.0.1,localhost,::1"

if [[ "$(node -p 'process.versions.node.split(".")[0]')" != 24 ]]; then
  echo 'Node 24 is required inside Ubuntu; Windows Node does not replace it.' >&2
  exit 1
fi
if ! java -version 2>&1 | head -n 1 | grep -Eq 'version "21[.]'; then
  echo 'Java 21 JDK is required inside Ubuntu.' >&2
  exit 1
fi
docker compose version >/dev/null
if ! docker info >/dev/null 2>&1; then
  echo 'Docker is unavailable. Check its service and your Ubuntu Docker permissions.' >&2
  exit 1
fi

case "$action" in
  check)
    java -version
    node --version
    npm --version
    python3 --version
    docker version --format '{{.Server.Version}}'
    docker compose version
    (cd backend && ./mvnw --version)
    ;;
  dev) exec bash ./scripts/dev.sh ;;
  test) exec bash ./scripts/test.sh ;;
  http)
    export CAMPUS_BASE_URL="${CAMPUS_BASE_URL:-http://127.0.0.1:5173}"
    exec python3 -m unittest discover -s tests -p test_system_http.py -v
    ;;
  database)
    export CAMPUS_DB_TEST=1
    exec python3 -m unittest discover -s tests -p test_database_boundary.py -v
    ;;
  browser-install)
    cd frontend
    npm ci
    exec npx playwright install --with-deps chromium
    ;;
  e2e)
    cd frontend
    exec npm run test:e2e
    ;;
esac
