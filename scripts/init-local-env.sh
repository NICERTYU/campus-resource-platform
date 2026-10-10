#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .local
if [[ ! -f .local/dev.env ]]; then
  umask 077
  python3 - <<'PY' > .local/dev.env
import secrets
for name in ('POSTGRES_PASSWORD', 'RESOURCE_DB_PASSWORD', 'WORKFLOW_DB_PASSWORD', 'MODULE_KEY'):
    print(name + '=' + secrets.token_hex(24))
print('WEB_PORT=8080\nDB_PORT=54329\nBUILD_COMMIT=local')
PY
fi
