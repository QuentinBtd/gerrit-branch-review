#!/usr/bin/env bash
# Idempotently prepares the development environment: verifies tools and pre-fetches the
# checksum-pinned Gerrit API artifacts for every supported series.
source "$(dirname "$0")/lib.sh"

bash "${ROOT}/tools/scripts/doctor.sh" || die "fix the failing checks above first"
cd "${ROOT}"
for s in "${SERIES_ALL[@]}"; do
  log "fetching verified build inputs for Gerrit ${s}"
  bazel fetch --config="gerrit-${s}" //:branch_review_deploy.jar //src/test/...
done
log "environment ready"
