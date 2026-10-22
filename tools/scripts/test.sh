#!/usr/bin/env bash
# Test entry point shared by mise tasks and CI.
#   test.sh unit                      fast unit tests (Gerrit 3.14 API)
#   test.sh integration [SERIES]      in-process acceptance tests + black-box SSH/HTTP tests
#   test.sh acceptance [SERIES]       in-process acceptance tests only
#   test.sh blackbox [SERIES]         black-box tests against the packaged JAR only
#   test.sh matrix                    unit + integration for every supported series
# Reports are collected in build/test-results/<series>/.
source "$(dirname "$0")/lib.sh"
cd "${ROOT}"

mode="${1:-unit}"
shift || true

collect() {
  local dest="${ROOT}/build/test-results/$1"
  mkdir -p "${dest}"
  if [[ -d "${ROOT}/bazel-testlogs" ]]; then
    (cd "${ROOT}/bazel-testlogs" && find -L . -name test.xml -print0) |
      while IFS= read -r -d '' f; do
        name="$(dirname "${f#./}" | tr '/' '.')"
        cp -f "${ROOT}/bazel-testlogs/${f}" "${dest}/TEST-${name}.xml"
      done
  fi
}

unit() {
  local s="${1:-${DEFAULT_SERIES}}"
  bazel test --config="gerrit-${s}" --test_tag_filters=unit //src/test/...
  collect "${s}"
}

acceptance() {
  local s="$1"
  check_series "$s"
  local rc=0
  bazel test --config="gerrit-${s}" --test_tag_filters=acceptance //src/test/... || rc=$?
  collect "${s}"
  return $rc
}

blackbox() {
  local s="$1" jar
  check_series "$s"
  jar="$(bash "${ROOT}/tools/scripts/build.sh" "$s" | tail -1)"
  python3 -I "${ROOT}/integration/blackbox.py" --series "$s" --jar "${jar}" \
    --out "${ROOT}/build/test-results/${s}/blackbox"
}

case "${mode}" in
  unit) unit "${1:-${DEFAULT_SERIES}}" ;;
  acceptance) acceptance "${1:-${DEFAULT_SERIES}}" ;;
  blackbox) blackbox "${1:-${DEFAULT_SERIES}}" ;;
  integration)
    s="${1:-${DEFAULT_SERIES}}"
    rc=0
    acceptance "$s" || rc=1
    blackbox "$s" || rc=1
    exit $rc
    ;;
  matrix)
    rc=0
    for s in "${SERIES_ALL[@]}"; do
      log "Gerrit ${s}"
      unit "$s" || rc=1
      acceptance "$s" || rc=1
      blackbox "$s" || rc=1
    done
    exit $rc
    ;;
  *) die "unknown test mode '${mode}'" ;;
esac
