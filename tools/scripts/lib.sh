#!/usr/bin/env bash
# Shared helpers for repository task scripts. Source, do not execute.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export ROOT
# shellcheck disable=SC2034 # used by the scripts that source this file
SERIES_ALL=(3.12 3.13 3.14)
# shellcheck disable=SC2034
DEFAULT_SERIES=3.14

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

log() {
  printf '==> %s\n' "$*" >&2
}

plugin_version() {
  tr -d '[:space:]' <"${ROOT}/version.txt"
}

check_series() {
  local s="$1" x
  for x in "${SERIES_ALL[@]}"; do
    [[ "$x" == "$s" ]] && return 0
  done
  die "unsupported Gerrit series '$s' (expected one of: ${SERIES_ALL[*]})"
}

# Exact Gerrit patch release for a series (tools/gerrit-releases.json is the single source).
gerrit_release() {
  python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["series"][sys.argv[2]]["version"])' \
    "${ROOT}/tools/gerrit-releases.json" "$1"
}

gerrit_image() {
  python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["series"][sys.argv[2]]["image"])' \
    "${ROOT}/tools/gerrit-releases.json" "$1"
}

bazel() {
  command bazelisk "$@"
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}
