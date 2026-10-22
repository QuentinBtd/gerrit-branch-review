#!/usr/bin/env bash
# Static checks for build files, scripts, workflows, configuration and documentation.
source "$(dirname "$0")/lib.sh"
cd "${ROOT}"

files() { git ls-files -z --cached --others --exclude-standard -- "$@" ':!:.mise/locks/**'; }
rc=0
run() {
  log "$*"
  "$@" || rc=1
}

files ':(glob)**/BUILD.bazel' ':(glob)**/*.bzl' 'MODULE.bazel' | xargs -0 -r buildifier -mode=check -lint=warn -warnings=-module-docstring || rc=1
files '*.sh' | xargs -0 -r shellcheck -x || rc=1
files '*.py' | xargs -0 -r ruff check || rc=1
run actionlint
run bash tools/scripts/check-config.sh
files '*.md' | xargs -0 -r markdownlint-cli2 || rc=1
exit $rc
