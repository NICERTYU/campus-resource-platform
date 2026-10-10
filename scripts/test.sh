#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "$(uname)" == Darwin ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; fi
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -v
(cd backend && ./mvnw -B verify)
(cd frontend && npm ci && npm run format:check && npm run test && npm run build)
