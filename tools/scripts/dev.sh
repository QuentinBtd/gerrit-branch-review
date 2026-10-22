#!/usr/bin/env bash
# Disposable local Gerrit for manual testing, owned by this task only.
#   dev.sh up [SERIES]   build the plugin, start Gerrit (127.0.0.1 only), print URLs
#   dev.sh down          remove the container and its volume
#   dev.sh logs          show server and plugin logs
# Ports: BRANCH_REVIEW_DEV_HTTP (default 8080), BRANCH_REVIEW_DEV_SSH (default 29418).
source "$(dirname "$0")/lib.sh"
cd "${ROOT}"

NAME="branch-review-dev"
LABEL="io.github.quentinbtd.branch-review.dev=1"
HTTP="${BRANCH_REVIEW_DEV_HTTP:-8080}"
SSHP="${BRANCH_REVIEW_DEV_SSH:-29418}"

case "${1:-}" in
  up)
    s="${2:-${DEFAULT_SERIES}}"
    check_series "$s"
    command -v docker >/dev/null || die "docker is required for dev:up"
    if docker inspect "${NAME}" >/dev/null 2>&1; then
      die "${NAME} already exists; run 'mise run dev:down' first"
    fi
    jar="$(bash tools/scripts/build.sh "$s" | tail -1)"
    docker volume create --label "${LABEL}" "${NAME}-site" >/dev/null
    docker create --name "${NAME}" --label "${LABEL}" \
      -p "127.0.0.1:${HTTP}:8080" -p "127.0.0.1:${SSHP}:29418" \
      -e "CANONICAL_WEB_URL=http://localhost:${HTTP}/" \
      -v "${NAME}-site:/var/gerrit" \
      "$(gerrit_image "$s")" >/dev/null
    docker cp "${jar}" "${NAME}:/var/gerrit/plugins/branch-review.jar"
    docker start "${NAME}" >/dev/null
    log "waiting for Gerrit"
    for _ in $(seq 1 150); do
      if curl -fsS "http://127.0.0.1:${HTTP}/config/server/version" >/dev/null 2>&1; then
        echo "Gerrit ${s}: http://localhost:${HTTP}/  (development login: become any account)"
        echo "SSH: ssh -p ${SSHP} <user>@localhost   Logs: mise run dev:logs"
        exit 0
      fi
      sleep 2
    done
    docker logs "${NAME}" | tail -50
    die "Gerrit did not become ready"
    ;;
  down)
    docker rm -f -v "${NAME}" >/dev/null 2>&1 || true
    docker volume rm "${NAME}-site" >/dev/null 2>&1 || true
    log "removed ${NAME}"
    ;;
  logs)
    docker logs "${NAME}" 2>&1 | tail -200
    docker exec "${NAME}" sh -c 'grep -h -i "branch-review\|branchreview" /var/gerrit/logs/error_log /var/gerrit/logs/sshd_log 2>/dev/null | tail -100' || true
    ;;
  *) die "usage: dev.sh up [SERIES] | down | logs" ;;
esac
