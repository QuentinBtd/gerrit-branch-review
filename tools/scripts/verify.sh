#!/usr/bin/env bash
# Local equivalent of the required pull-request checks. Never publishes anything.
#   verify.sh            formatting, hooks, lint, unit tests, build of every series
#   verify.sh --full     additionally the integration matrix (acceptance + black-box)
source "$(dirname "$0")/lib.sh"
cd "${ROOT}"

log "formatting"
bash tools/scripts/fmt.sh --check
log "hooks"
prek run --all-files --show-diff-on-failure
log "lint"
bash tools/scripts/lint.sh
log "unit tests"
bash tools/scripts/test.sh unit
log "build matrix"
bash tools/scripts/build.sh all >/dev/null
if [[ "${1:-}" == "--full" ]]; then
  log "integration matrix"
  bash tools/scripts/test.sh matrix
fi
log "verify passed"
