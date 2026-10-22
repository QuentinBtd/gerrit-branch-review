#!/usr/bin/env bash
# Runs the pinned Renovate container (same image as .github/workflows/renovate.yml).
#   renovate.sh validate   validate .renovaterc.jsonc and the bot configuration (offline)
#   renovate.sh extract    list every dependency Renovate extracts from this checkout (offline,
#                          --platform=local, no credentials, no writes)
#   renovate.sh dry-run    lookup-only run against GitHub: shows proposed updates, writes nothing
#                          (needs RENOVATE_TOKEN with read access)
#   renovate.sh run        real run with the bot configuration (needs RENOVATE_TOKEN with the
#                          permissions documented in docs/renovate.md)
source "$(dirname "$0")/lib.sh"
cd "${ROOT}"

IMAGE="ghcr.io/renovatebot/renovate:44.145.1-full@sha256:2ead179919ff9cf3a2486a8667b26446138d1b9329823f674543a493afc3e1e4"
command -v docker >/dev/null || die "docker is required for Renovate tasks"

run_image() {
  docker run --rm -t --init \
    -e LOG_LEVEL="${LOG_LEVEL:-info}" \
    -v "${ROOT}:/usr/src/app:ro" -w /usr/src/app \
    "$@"
}

case "${1:-}" in
  validate)
    run_image --entrypoint renovate-config-validator "${IMAGE}" --strict .renovaterc.jsonc
    run_image -e RENOVATE_CONFIG_FILE=/usr/src/app/.github/renovate/bot-config.json5 \
      --entrypoint renovate-config-validator "${IMAGE}" --strict
    ;;
  extract)
    out="${ROOT}/build/renovate"
    rm -rf "${out}"
    mkdir -p "${out}/snapshot"
    # The local platform reads committed files: extract from a throwaway commit of the
    # current tree (tracked and new, non-ignored files) instead of touching this repository.
    git ls-files -z --cached --others --exclude-standard | grep -zv '^build/' |
      xargs -0 -I{} cp --parents {} "${out}/snapshot/" 2>/dev/null ||
      (git ls-files -z --cached --others --exclude-standard | grep -zv '^build/' |
        while IFS= read -r -d '' f; do mkdir -p "${out}/snapshot/$(dirname "$f")" && cp "$f" "${out}/snapshot/$f"; done)
    git -C "${out}/snapshot" init -q -b main
    git -C "${out}/snapshot" -c user.name=snapshot -c user.email=snapshot@localhost add -A
    git -C "${out}/snapshot" -c user.name=snapshot -c user.email=snapshot@localhost commit -q -m snapshot
    docker run --rm --init -e LOG_LEVEL=debug -e LOG_FORMAT=json \
      -e RENOVATE_PLATFORM=local -e RENOVATE_DRY_RUN=extract -e RENOVATE_REPORT_TYPE=file \
      -e RENOVATE_REPORT_PATH=/tmp/report/report.json -e RENOVATE_ONBOARDING=false \
      -e RENOVATE_REQUIRE_CONFIG=optional \
      -v "${out}/snapshot:/usr/src/app" -v "${out}:/tmp/report" -w /usr/src/app \
      "${IMAGE}" >"${out}/extract.log" 2>&1 || die "extraction failed, see ${out}/extract.log"
    python3 -I tools/scripts/renovate_report.py "${out}/report.json"
    ;;
  dry-run | run)
    : "${RENOVATE_TOKEN:?set RENOVATE_TOKEN (see docs/renovate.md)}"
    mode=()
    [[ "$1" == "dry-run" ]] && mode=(-e RENOVATE_DRY_RUN=lookup)
    docker run --rm -t --init -e LOG_LEVEL="${LOG_LEVEL:-info}" \
      -e RENOVATE_TOKEN -e RENOVATE_CONFIG_FILE=/config/bot-config.json5 \
      -e RENOVATE_GIT_AUTHOR "${mode[@]}" \
      -v "${ROOT}/.github/renovate/bot-config.json5:/config/bot-config.json5:ro" \
      "${IMAGE}"
    ;;
  *) die "usage: renovate.sh validate | extract | dry-run | run" ;;
esac
