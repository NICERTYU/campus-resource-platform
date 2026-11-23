#!/usr/bin/env bash
set -euo pipefail
url="${1%/}"
[[ "$url" == https://* ]] || { echo '[deploy] SMOKE_URL must use HTTPS' >&2; exit 2; }
response="$(mktemp)"
trap 'rm -f "$response"' EXIT
curl_args=(--silent --show-error --connect-timeout 10 --max-time 30 --retry 2 --retry-max-time 30)

status="$(curl "${curl_args[@]}" --fail -o /dev/null -w '%{http_code}' "$url/")"
[[ "$status" == 200 ]] || { echo '[deploy] Public home page did not return 200' >&2; exit 1; }
curl "${curl_args[@]}" --fail -o "$response" "$url/api/v1/auth/csrf"
python3 -c 'import json,sys; assert json.load(open(sys.argv[1]))["token"]' "$response"
status="$(curl "${curl_args[@]}" -o "$response" -w '%{http_code}' "$url/api/v1/classroom-applications")"
[[ "$status" == 401 ]] || { echo '[deploy] Public workflow endpoint did not return 401' >&2; exit 1; }
python3 -c 'import json,sys; assert json.load(open(sys.argv[1]))["code"] == "UNAUTHENTICATED"' "$response"
