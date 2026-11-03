#!/usr/bin/env bash
# Runs on the CI runner. Secrets stay in a private, short-lived SSH directory.
set -euo pipefail
cd "$(dirname "$0")/.."
project="${1:?Usage: install-verified-release.sh campus-test|campus-prod}"
[[ "$project" == campus-test || "$project" == campus-prod ]] || exit 2
: "${SSH_HOST:?}" "${SSH_USER:?}" "${SSH_KEY:?}" "${SSH_KNOWN_HOSTS:?}"
: "${DEPLOY_ROOT:?}" "${DEPLOY_ENV_FILE:?}" "${GITHUB_SHA:?}"
[[ "$GITHUB_SHA" =~ ^[0-9a-f]{40}$ && "$DEPLOY_ROOT" == /* && "$DEPLOY_ENV_FILE" == /* ]] || exit 2
upload_seconds="${DEPLOY_UPLOAD_TIMEOUT_SECONDS:-600}"
[[ "$upload_seconds" =~ ^[1-9][0-9]{0,3}$ ]] && ((upload_seconds <= 3600)) || exit 2
for command in ssh scp timeout; do command -v "$command" >/dev/null; done
source_archive=".local/artifact/source-$GITHUB_SHA.tar.gz"
image_archive=".local/artifact/images-$GITHUB_SHA.tar.gz"
[[ -s "$source_archive" && -s "$image_archive" ]] || { echo '[deploy] Missing verified release archives' >&2; exit 2; }
umask 077
ssh_dir=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/campus-ssh.XXXXXX")
heartbeat_pid=''
phase='prepare SSH'
cleanup() {
  result=$?
  trap - EXIT
  if [[ -n "$heartbeat_pid" ]]; then kill "$heartbeat_pid" 2>/dev/null || true; wait "$heartbeat_pid" 2>/dev/null || true; fi
  rm -f "$ssh_dir/key" "$ssh_dir/known_hosts"
  rmdir "$ssh_dir"
  if ((result != 0)); then printf '[deploy] FAILED: %s (exit %s)\n' "$phase" "$result" >&2; fi
  exit "$result"
}
trap cleanup EXIT
printf '%s\n' "$SSH_KEY" > "$ssh_dir/key"
printf '%s\n' "$SSH_KNOWN_HOSTS" > "$ssh_dir/known_hosts"
ssh_options=(-i "$ssh_dir/key" -o "UserKnownHostsFile=$ssh_dir/known_hosts"
  -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes
  -o ConnectTimeout=15 -o ConnectionAttempts=1
  -o ServerAliveInterval=15 -o ServerAliveCountMax=3)
heartbeat() {
  sleeper=''
  trap 'if [[ -n "$sleeper" ]]; then kill "$sleeper" 2>/dev/null || true; wait "$sleeper" 2>/dev/null || true; fi; exit 0' TERM INT
  while true; do
    sleep 15 &
    sleeper=$!
    wait "$sleeper"
    sleeper=''
    printf '[deploy] WAIT: %s (%ss elapsed)\n' "$phase" "$((SECONDS - started))"
  done
}
run_phase() {
  phase="$1"; limit="$2"; shift 2
  printf '[deploy] START: %s (limit %ss)\n' "$phase" "$limit"
  started=$SECONDS
  heartbeat &
  heartbeat_pid=$!
  result=0
  timeout --signal=TERM --kill-after=10s "${limit}s" "$@" || result=$?
  kill "$heartbeat_pid" 2>/dev/null || true
  wait "$heartbeat_pid" 2>/dev/null || true
  heartbeat_pid=''
  if ((result != 0)); then
    if ((result == 124 || result == 137)); then printf '[deploy] TIMEOUT: %s\n' "$phase" >&2; fi
    return "$result"
  fi
  printf '[deploy] DONE: %s (%ss elapsed)\n' "$phase" "$((SECONDS - started))"
}
run_phase 'SSH connection and incoming directory' 30 \
  ssh "${ssh_options[@]}" -p "${SSH_PORT:-22}" "$SSH_USER@$SSH_HOST" 'mkdir -p incoming' </dev/null
upload_bytes=$(( $(wc -c < "$source_archive") + $(wc -c < "$image_archive") ))
printf '[deploy] Upload size: %s bytes\n' "$upload_bytes"
run_phase 'upload verified source and images' "$upload_seconds" \
  scp "${ssh_options[@]}" -P "${SSH_PORT:-22}" "$source_archive" "$image_archive" "$SSH_USER@$SSH_HOST:incoming/" </dev/null
printf -v remote_cmd 'bash -s -- %q %q %q %q' "$DEPLOY_ROOT" "$GITHUB_SHA" "$DEPLOY_ENV_FILE" "$project"
run_phase 'remote image installation and service health' 600 \
  ssh "${ssh_options[@]}" -p "${SSH_PORT:-22}" "$SSH_USER@$SSH_HOST" "$remote_cmd" < deploy/remote-install.sh
echo '[deploy] Verified release installation completed'
