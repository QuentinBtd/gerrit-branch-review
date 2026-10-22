#!/usr/bin/env bash
# Release operations shared by mise tasks and GitHub Actions.
#
#   release.sh plan                      Release Please dry run (needs GITHUB_TOKEN, read access)
#   release.sh verify VERSION [REV]      version/tag/source consistency + release gates
#   release.sh build VERSION [REV]       build assets from a clean checkout of REV into
#                                        dist/release/VERSION (no network writes)
#   release.sh publish VERSION           upload dist/release/VERSION to GitHub release vVERSION
#                                        (needs GH_TOKEN with contents:write; idempotent)
#
# VERSION is X.Y.Z (stable, must equal version.txt at REV) or X.Y.Z-rc.N (explicit prerelease,
# X.Y.Z must be greater than version.txt at REV). REV defaults to HEAD.
source "$(dirname "$0")/lib.sh"
cd "${ROOT}"

REPO="${GITHUB_REPOSITORY:-QuentinBtd/gerrit-branch-review}"

is_stable() { [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; }
is_rc() { [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+-rc\.[1-9][0-9]*$ ]]; }

semver_gt() {
  python3 -I -c '
import sys
a = tuple(map(int, sys.argv[1].split(".")))
b = tuple(map(int, sys.argv[2].split(".")))
sys.exit(0 if a > b else 1)' "$1" "$2"
}

check_version() {
  local version="$1" rev="$2" file_version
  file_version="$(git show "${rev}:version.txt" | tr -d '[:space:]')"
  if is_stable "${version}"; then
    [[ "${version}" == "${file_version}" ]] ||
      die "version ${version} does not match version.txt (${file_version}) at ${rev}"
    local manifest
    manifest="$(git show "${rev}:.release-please-manifest.json" | python3 -I -c 'import json,sys; print(json.load(sys.stdin).get(".", ""))')"
    [[ "${manifest}" == "${version}" ]] ||
      die "Release Please manifest (${manifest}) does not match ${version} at ${rev}"
    # Release Please writes "## [X.Y.Z](compare-link) (date)", or "## X.Y.Z (date)" for the first.
    git show "${rev}:CHANGELOG.md" | grep -Eq "^## \[?${version//./\\.}\]?[ (]" ||
      die "CHANGELOG.md at ${rev} has no entry for ${version}"
  elif is_rc "${version}"; then
    semver_gt "${version%%-*}" "${file_version}" ||
      die "prerelease ${version} must precede a version greater than ${file_version}"
  else
    die "invalid version '${version}' (X.Y.Z or X.Y.Z-rc.N)"
  fi
}

check_tag() {
  local version="$1" rev="$2" tag="v$1" commit
  commit="$(git rev-parse "${rev}^{commit}")"
  if git rev-parse -q --verify "refs/tags/${tag}" >/dev/null; then
    [[ "$(git rev-parse "refs/tags/${tag}^{commit}")" == "${commit}" ]] ||
      die "tag ${tag} exists but points to $(git rev-parse "refs/tags/${tag}^{commit}"), not ${commit}"
  fi
}

case "${1:-}" in
  plan)
    : "${GITHUB_TOKEN:?set GITHUB_TOKEN (read access to ${REPO}) for release:plan}"
    release-please release-pr --dry-run --repo-url="${REPO}" --token="${GITHUB_TOKEN}" \
      --config-file=release-please-config.json --manifest-file=.release-please-manifest.json \
      --target-branch="${RELEASE_BRANCH:-main}"
    ;;
  verify)
    version="${2:?usage: release.sh verify VERSION [REV]}"
    rev="${3:-HEAD}"
    check_version "${version}" "${rev}"
    check_tag "${version}" "${rev}"
    [[ "$(git rev-parse "${rev}^{commit}")" == "$(git rev-parse HEAD)" ]] ||
      die "check out ${rev} to run the release gates against it"
    [[ -z "$(git status --porcelain)" ]] || die "working tree is not clean"
    log "release gates for ${version} at $(git rev-parse --short HEAD)"
    bash tools/scripts/verify.sh
    if [[ "${RELEASE_SKIP_MATRIX:-}" != "1" ]]; then
      bash tools/scripts/test.sh matrix
    fi
    log "release ${version} verified"
    ;;
  build)
    version="${2:?usage: release.sh build VERSION [REV]}"
    rev="${3:-HEAD}"
    check_version "${version}" "${rev}"
    check_tag "${version}" "${rev}"
    commit="$(git rev-parse "${rev}^{commit}")"
    work="${ROOT}/build/release-src/${commit}"
    rm -rf "${work}"
    mkdir -p "$(dirname "${work}")"
    git worktree prune
    git worktree add --detach "${work}" "${commit}" >/dev/null
    trap 'git -C "${ROOT}" worktree remove --force "${work}" >/dev/null 2>&1 || true' EXIT
    if is_rc "${version}"; then
      (cd "${work}" && BRANCH_REVIEW_VERSION="${version}" bash tools/scripts/package.sh)
    else
      (cd "${work}" && bash tools/scripts/package.sh)
    fi
    out="${ROOT}/dist/release/${version}"
    rm -rf "${out}"
    mkdir -p "${out}"
    cp "${work}"/dist/branch-review-"${version}"* "${out}/"
    (cd "${out}" && if command -v sha256sum >/dev/null; then sha256sum -c "branch-review-${version}.sha256"; else shasum -a 256 -c "branch-review-${version}.sha256"; fi)
    log "release assets for ${version} (${commit}) in ${out}"
    ;;
  publish)
    version="${2:?usage: release.sh publish VERSION}"
    tag="v${version}"
    out="${ROOT}/dist/release/${version}"
    : "${GH_TOKEN:?set GH_TOKEN (contents:write on ${REPO}) for release:publish}"
    [[ -f "${out}/branch-review-${version}.sha256" ]] || die "run release:build ${version} first"
    (cd "${out}" && if command -v sha256sum >/dev/null; then sha256sum -c "branch-review-${version}.sha256"; else shasum -a 256 -c "branch-review-${version}.sha256"; fi) >/dev/null ||
      die "local assets do not match their checksum file"
    if ! gh release view "${tag}" --repo "${REPO}" >/dev/null 2>&1; then
      is_rc "${version}" || die "release ${tag} does not exist; Release Please creates stable releases"
      target="$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["sourceRevision"])' "${out}/branch-review-${version}-build-info.json")"
      gh release create "${tag}" --repo "${REPO}" --target "${target}" --prerelease \
        --title "branch-review ${version}" --notes "Release candidate ${version}. Not for production use."
    fi
    existing="$(mktemp -d)"
    trap 'rm -rf "${existing}"' EXIT
    for f in "${out}"/branch-review-"${version}"*; do
      name="$(basename "$f")"
      if gh release download "${tag}" --repo "${REPO}" --pattern "${name}" --dir "${existing}" >/dev/null 2>&1; then
        if [[ "$(sha256_of "${existing}/${name}")" == "$(sha256_of "$f")" ]]; then
          log "${name}: already published, identical"
          continue
        fi
        die "${name}: a different asset is already published under ${tag}; refusing to overwrite"
      fi
      gh release upload "${tag}" "$f" --repo "${REPO}"
      log "${name}: uploaded"
    done
    log "published ${tag}"
    ;;
  *) die "usage: release.sh plan | verify VERSION [REV] | build VERSION [REV] | publish VERSION" ;;
esac
