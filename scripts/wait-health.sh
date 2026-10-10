#!/usr/bin/env bash
set -euo pipefail
for attempt in $(seq 1 60); do
  if curl -fsS "$1" >/dev/null; then exit 0; fi
  sleep 1
done
echo "服务未就绪：$1" >&2
exit 1
