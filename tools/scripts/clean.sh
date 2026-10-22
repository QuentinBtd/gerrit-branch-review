#!/usr/bin/env bash
# Removes generated project files: dist/, build/ and Bazel's convenience symlinks.
# --all also expunges this workspace's Bazel output base (shared caches are kept).
source "$(dirname "$0")/lib.sh"
cd "${ROOT}"
rm -rf dist build
for l in bazel-bin bazel-out bazel-testlogs bazel-gerrit-branch-review bazel-"$(basename "${ROOT}")"; do
  [[ -L "$l" ]] && rm -f "$l"
done
if [[ "${1:-}" == "--all" ]]; then
  bazel clean --expunge
fi
log "clean"
