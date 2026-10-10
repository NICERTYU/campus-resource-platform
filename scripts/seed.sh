#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
environment_file="${1:?Usage: scripts/seed.sh ENV_FILE [COMPOSE_PROJECT]}"
project="${2:-campus-dev}"
docker compose --env-file "$environment_file" -p "$project" -f deploy/compose.yml run --rm --no-deps resource --campus.seed=true --server.port=0
