#!/usr/bin/env bash
# Validates repository configuration files with parsers/schemas that understand each format.
source "$(dirname "$0")/lib.sh"
cd "${ROOT}"
rc=0

# Release Please configuration against the schema of the pinned release-please version.
rp_dir="$(dirname "$(dirname "$(command -v release-please)")")"
schema="$(find "${rp_dir}" -path '*node_modules/release-please/schemas/config.json' -print -quit 2>/dev/null || true)"
if [[ -z "${schema}" ]]; then
  schema="$(npm root -g 2>/dev/null)/release-please/schemas/config.json"
fi
if [[ -f "${schema}" ]]; then
  check-jsonschema --schemafile "${schema}" release-please-config.json || rc=1
else
  printf 'error: release-please schema not found next to the installed CLI\n' >&2
  rc=1
fi
python3 -I tools/scripts/check_release_manifest.py || rc=1

# Example plugin configuration must parse with the plugin's own strict rules (via git config).
git config -f examples/branch-review.config --list >/dev/null || rc=1

# Version source sanity.
grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' version.txt || {
  echo "version.txt must hold a plain SemVer version" >&2
  rc=1
}
exit $rc
