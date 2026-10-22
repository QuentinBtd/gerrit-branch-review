#!/usr/bin/env bash
# Builds every supported series and collects release-shaped assets in dist/:
#   branch-review-<version>-gerrit-<series>.jar   one JAR per Gerrit series
#   branch-review-<version>.sha256                 SHA-256 of every asset above
#   branch-review-<version>-build-info.json        source revision, versions, toolchain
#   branch-review-<version>-THIRD-PARTY.txt        bundled code inventory and licenses
# Each JAR is checked (manifest identity/version/API, bundled classes) before it is kept.
source "$(dirname "$0")/lib.sh"
cd "${ROOT}"

version="${BRANCH_REVIEW_VERSION:-$(plugin_version)}"
rm -f dist/branch-review-"${version}"*
jars=()
for s in "${SERIES_ALL[@]}"; do
  jars+=("$(bash tools/scripts/build.sh "$s" | tail -1)")
done

for i in "${!SERIES_ALL[@]}"; do
  python3 -I tools/scripts/check_jar.py "${jars[$i]}" "${version}" "$(gerrit_release "${SERIES_ALL[$i]}")"
done

revision="$(git rev-parse --verify -q HEAD 2>/dev/null || echo unknown)"
dirty=false
[[ -n "$(git status --porcelain 2>/dev/null)" ]] && dirty=true
python3 -I - "${version}" "${revision}" "${dirty}" >"dist/branch-review-${version}-build-info.json" <<'PY'
import json, platform, subprocess, sys
def out(*cmd):
    try:
        return subprocess.run(cmd, capture_output=True, text=True).stdout.strip() or subprocess.run(cmd, capture_output=True, text=True).stderr.strip()
    except OSError:
        return None
version, revision, dirty = sys.argv[1:]
series = {}
for k, r in json.load(open("tools/gerrit-releases.json"))["series"].items():
    series[k] = {"gerrit": r["version"], "gerritTagCommit": r["tagCommit"],
                 "asset": "branch-review-%s-gerrit-%s.jar" % (version, k)}
info = {
    "plugin": "branch-review",
    "version": version,
    "sourceRevision": revision,
    "dirtyWorkingTree": dirty == "true",
    "gerrit": series,
    "javaTarget": "21",
    "toolchain": {
        "bazel": open(".bazelversion").read().strip(),
        "bazelisk": out("bazelisk", "version").splitlines()[0] if out("bazelisk", "version") else None,
        "javaRuntimeForBazel": "remotejdk_21 (rules_java)",
        "host": platform.platform(),
    },
}
print(json.dumps(info, indent=2, sort_keys=True))
PY

cat >"dist/branch-review-${version}-THIRD-PARTY.txt" <<TXT
branch-review ${version} — bundled code inventory

The plugin JARs contain only classes and resources of branch-review itself
(Apache-2.0, see LICENSE inside each JAR). No third-party code is bundled.

At runtime the plugin uses APIs provided by the Gerrit server it is loaded into
(gerrit-plugin-api, which includes JGit, Guice, Guava, Gson, Flogger, args4j;
all distributed with Gerrit under their own licenses). These are compile-time
"neverlink" dependencies and are not redistributed in the release assets.
TXT

(
  cd dist
  : >"branch-review-${version}.sha256"
  for f in branch-review-"${version}"-gerrit-*.jar "branch-review-${version}-build-info.json" "branch-review-${version}-THIRD-PARTY.txt"; do
    printf '%s  %s\n' "$(sha256_of "$f")" "$f" >>"branch-review-${version}.sha256"
  done
  cat "branch-review-${version}.sha256"
)
log "assets in ${ROOT}/dist"
