#!/usr/bin/env bash
# Applies (or with --check, verifies) formatting of tracked source files.
source "$(dirname "$0")/lib.sh"

check=false
[[ "${1:-}" == "--check" ]] && check=true
cd "${ROOT}"

# Tracked and new files, excluding generated lock material under .mise/locks.
files() { git ls-files -z --cached --others --exclude-standard -- "$@" ':!:.mise/locks/**'; }

rc=0
if $check; then
  files '*.java' | xargs -0 -r google-java-format --dry-run --set-exit-if-changed || rc=1
  files ':(glob)**/BUILD.bazel' ':(glob)**/*.bzl' 'MODULE.bazel' | xargs -0 -r buildifier -mode=check -lint=off || rc=1
  files '*.sh' | xargs -0 -r shfmt -d -i 2 -ci || rc=1
  files '*.py' | xargs -0 -r ruff format --check || rc=1
  files '*.toml' | xargs -0 -r taplo format --check || rc=1
else
  files '*.java' | xargs -0 -r google-java-format --replace
  files ':(glob)**/BUILD.bazel' ':(glob)**/*.bzl' 'MODULE.bazel' | xargs -0 -r buildifier -mode=fix -lint=off
  files '*.sh' | xargs -0 -r shfmt -w -i 2 -ci
  files '*.py' | xargs -0 -r ruff format
  files '*.toml' | xargs -0 -r taplo format
fi
exit $rc
