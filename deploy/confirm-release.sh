#!/usr/bin/env bash
# Runs on the deployment host after the runner has checked the public endpoint.
set -euo pipefail
deploy_root="${1:?}"; project="${2:?}"; revision="${3:?}"
[[ "$deploy_root" == /* && "$revision" =~ ^[0-9a-f]{40}$ ]] || exit 2
[[ "$project" == campus-test || "$project" == campus-prod ]] || exit 2
exec 9>"$deploy_root/locks/release-images.lock"
flock -w 180 9
if [[ ! -f "$deploy_root/$project.current" || "$(<"$deploy_root/$project.current")" != "$revision" ]]; then
  echo '[deploy] Installed version changed during public verification; skipping confirmation and cleanup' >&2
  exit 1
fi

verified_file="$deploy_root/$project.verified"
printf '%s\n' "$revision" > "$verified_file.tmp"
mv "$verified_file.tmp" "$verified_file"
printf '[deploy] Public endpoint verified for %s %s\n' "$project" "$revision"

prune_old_images() {
  protected_revisions=' '
  for environment in campus-test campus-prod; do
    for marker in current previous verified; do
      path="$deploy_root/$environment.$marker"
      [[ -f "$path" ]] || continue
      protected="$(<"$path")"
      if [[ ! "$protected" =~ ^[0-9a-f]{40}$ ]]; then
        printf '[deploy] Invalid release marker: %s; skipping image cleanup\n' "$path" >&2
        return 1
      fi
      protected_revisions+="$protected "
    done
  done

  # Protect running and stopped containers, including containers created using an image ID.
  container_ids="$(docker ps -aq)" || return 1
  protected_ids=' '
  while read -r container; do
    [[ -n "$container" ]] || continue
    image_id="$(docker container inspect --format '{{.Image}}' "$container")" || return 1
    protected_ids+="$image_id "
  done <<< "$container_ids"

  images="$(docker image ls --no-trunc --format '{{.Repository}} {{.Tag}} {{.ID}}')" || return 1
  failed=0
  while read -r repository tag image_id; do
    [[ "$repository" == campus-resource || "$repository" == campus-workflow || "$repository" == campus-web ]] || continue
    [[ "$tag" =~ ^[0-9a-f]{40}$ ]] || continue
    [[ "$protected_revisions" != *" $tag "* && "$protected_ids" != *" $image_id "* ]] || continue
    # Only remove tags recorded as installed by this deployment root.
    release="$deploy_root/releases/$tag"
    [[ -f "$release/migrations-campus-test.txt" || -f "$release/migrations-campus-prod.txt" ]] || continue
    if ! docker image rm "$repository:$tag"; then
      printf '[deploy] Could not remove old image %s:%s\n' "$repository" "$tag" >&2
      failed=1
    fi
  done <<< "$images"
  return "$failed"
}

if ! prune_old_images; then
  echo '::warning::Some old images could not be cleaned. Check the deployment log.' >&2
fi
