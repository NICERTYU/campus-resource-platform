#!/usr/bin/env bash
set -euo pipefail
usage() {
  echo "Usage: $0 HTTP_URL [TIMEOUT_SECONDS (1-86400; default 60)]" >&2
  exit 2
}
[[ $# -ge 1 && $# -le 2 ]] || usage
url=$1
timeout_seconds=${2-60}
[[ "$url" =~ ^https?://[^/?#[:space:]]+([/?#][^[:space:]]*)?$ ]] || usage
[[ "$timeout_seconds" =~ ^[1-9][0-9]{0,4}$ ]] || usage
(( timeout_seconds <= 86400 )) || usage
deadline=$((SECONDS + timeout_seconds))
while :; do
  remaining=$((deadline - SECONDS))
  (( remaining > 0 )) || break
  request_timeout=$remaining
  if (( request_timeout > 5 )); then request_timeout=5; fi
  if curl -fsS --connect-timeout "$request_timeout" --max-time "$request_timeout" -- "$url" >/dev/null; then
    exit 0
  fi
  if (( SECONDS < deadline )); then sleep 1; fi
done
echo "服务未就绪（等待${timeout_seconds}秒）：$url" >&2
exit 1
