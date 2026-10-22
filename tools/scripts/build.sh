#!/usr/bin/env bash
# Builds the plugin JAR for one or more Gerrit series and copies it to dist/.
# Usage: build.sh [SERIES...]   (default: 3.14; "all" builds every supported series)
source "$(dirname "$0")/lib.sh"

series=("$@")
if [[ ${#series[@]} -eq 0 ]]; then
  series=("${DEFAULT_SERIES}")
elif [[ "${series[0]}" == "all" ]]; then
  series=("${SERIES_ALL[@]}")
fi

# BRANCH_REVIEW_VERSION may name an explicit prerelease (validated by the Bazel version rule).
version="${BRANCH_REVIEW_VERSION:-$(plugin_version)}"
mkdir -p "${ROOT}/dist"
for s in "${series[@]}"; do
  check_series "$s"
  log "building branch-review ${version} for Gerrit ${s}"
  (cd "${ROOT}" && bazel build --config="gerrit-${s}" \
    --repo_env=BRANCH_REVIEW_VERSION="${BRANCH_REVIEW_VERSION:-}" //:branch_review_deploy.jar)
  out="${ROOT}/dist/branch-review-${version}-gerrit-${s}.jar"
  cp -f "${ROOT}/bazel-bin/branch_review_deploy.jar" "${out}"
  chmod 0644 "${out}"
  printf '%s\n' "${out}"
done
