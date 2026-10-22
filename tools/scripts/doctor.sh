#!/usr/bin/env bash
# Reports the pinned toolchain, host prerequisites and container availability.
source "$(dirname "$0")/lib.sh"

status=0
ok() { printf '  ok    %-22s %s\n' "$1" "$2"; }
bad() {
  printf '  FAIL  %-22s %s\n' "$1" "$2"
  status=1
}
warn() { printf '  warn  %-22s %s\n' "$1" "$2"; }

echo "branch-review $(plugin_version) — environment check"
echo
echo "Host prerequisites (not installed by mise):"
if command -v git >/dev/null; then ok git "$(git --version)"; else bad git "install Git >= 2.30"; fi
if command -v ssh >/dev/null && command -v ssh-keygen >/dev/null; then
  ok openssh "$(ssh -V 2>&1)"
else
  bad openssh "install an OpenSSH client (black-box tests use ssh and ssh-keygen)"
fi
if command -v docker >/dev/null; then
  if docker info >/dev/null 2>&1; then
    ok docker "$(docker version --format '{{.Server.Version}} ({{.Server.Arch}})' 2>/dev/null)"
  else
    warn docker "CLI found but the daemon is not reachable; black-box tests, dev:up and renovate tasks need it"
  fi
else
  warn docker "not found; needed only for test:integration black-box tests, dev:up and renovate tasks"
fi
case "$(uname -s)" in
  Linux | Darwin) ok platform "$(uname -sm)" ;;
  *) warn platform "$(uname -sm) is untested; use Linux or WSL2" ;;
esac

echo
echo "Pinned tools (from .mise.toml / mise.lock):"
if ! command -v mise >/dev/null; then
  bad mise "install mise (see docs/development.md)"
else
  ok mise "$(mise --version 2>/dev/null | head -1)"
  missing="$(mise ls --current --missing 2>/dev/null || true)"
  if [[ -n "${missing}" ]]; then
    bad "mise tools" "missing — run: mise install"
    printf '%s\n' "${missing}" | sed 's/^/        /'
  fi
fi
for t in java bazelisk prek google-java-format buildifier shellcheck shfmt actionlint taplo ruff python3 node release-please markdownlint-cli2 check-jsonschema gh; do
  if p="$(command -v "$t" 2>/dev/null)"; then
    case "$t" in
      java) v="$(java -version 2>&1 | head -1)" ;;
      bazelisk) v="$(bazelisk version 2>/dev/null | grep -i '^bazelisk version' | head -1 || true)" ;;
      *) v="$("$t" --version 2>&1 | head -1)" ;;
    esac
    ok "$t" "${v:-${p}}"
  else
    bad "$t" "not on PATH; run inside mise (mise run ..., or mise exec -- ...)"
  fi
done

if command -v java >/dev/null && ! java -version 2>&1 | grep -q '"21\.'; then
  bad java "Java 21 is required (Gerrit 3.12-3.14 runtime and build)"
fi
if [[ -f "${ROOT}/.bazelversion" ]]; then
  ok ".bazelversion" "$(cat "${ROOT}/.bazelversion")"
fi
avail_kb="$(df -Pk "${ROOT}" | awk 'NR==2 {print $4}')"
if [[ "${avail_kb}" -lt $((10 * 1024 * 1024)) ]]; then
  warn disk "less than 10 GiB free; the Bazel cache and Gerrit images need several GiB"
else
  ok disk "$((avail_kb / 1024 / 1024)) GiB free"
fi
echo
if [[ $status -ne 0 ]]; then
  echo "Some checks failed."
else
  echo "All required checks passed."
fi
exit $status
