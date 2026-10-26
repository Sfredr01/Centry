#!/usr/bin/env bash
# Tests scripts/upgrade.sh against a real compose stack and a throwaway local registry:
#   1. good -> good upgrade succeeds, writes a backup, keeps data, prunes old backups to 14;
#   2. good -> bad upgrade (the bad image corrupts the database, then exits 1) fails non-zero, restores the
#      backup and the previous tag, and the app is healthy again with its data;
#   3. an unpullable tag fails before anything is stopped or changed.
#
# Needs the image centry:smoke (built by scripts/smoke.sh; built here if missing).
# Env: CENTRY_PORT (default 8080), CENTRY_TEST_REGISTRY_PORT (default 15000), KEEP=1 to keep the temp dir.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
PORT=${CENTRY_PORT:-8080}
REGISTRY_PORT=${CENTRY_TEST_REGISTRY_PORT:-15000}
REGISTRY_NAME=centry-upgrade-test-registry
REPO="localhost:${REGISTRY_PORT}/centry"
BASE="http://localhost:${PORT}"
ADMIN_USER="admin"
ADMIN_PASSWORD=upgrade-test-password-123

WORK=$(mktemp -d "${TMPDIR:-/tmp}/centry-upgrade-test.XXXXXX")
INSTALL="${WORK}/install"
DATA="${WORK}/data"
mkdir -p "${INSTALL}/scripts" "${DATA}"
# The container runs as uid 10001; a world-writable dir works without root on the host.
chmod 777 "${DATA}"
cp "${ROOT}/docker-compose.yml" "${INSTALL}/"
cp "${ROOT}/scripts/upgrade.sh" "${INSTALL}/scripts/upgrade.sh" 2>/dev/null || true
cat > "${INSTALL}/.env" <<ENV
CENTRY_ADMIN_USERNAME=${ADMIN_USER}
CENTRY_ADMIN_PASSWORD=${ADMIN_PASSWORD}
CENTRY_TAG=good
CENTRY_IMAGE=${REPO}
CENTRY_DATA=${DATA}
CENTRY_PORT=${PORT}
ENV
export COMPOSE_PROJECT_NAME=centry-upgrade-test

dc() { (cd "${INSTALL}" && docker compose -f docker-compose.yml "$@"); }

cleanup() {
    status=$?
    if [ "${KEEP:-0}" = 1 ]; then
        echo "KEEP=1: stack left running, temp dir ${WORK}"
        exit "${status}"
    fi
    dc down -v --remove-orphans >/dev/null 2>&1 || true
    docker rm -f "${REGISTRY_NAME}" >/dev/null 2>&1 || true
    # Files written by the container belong to uid 10001: remove them from inside a container.
    docker run --rm -v "${DATA}:/data" --entrypoint sh centry:smoke -c 'rm -rf /data/* /data/.[!.]*' >/dev/null 2>&1 || true
    rm -rf "${WORK}" 2>/dev/null || true
    exit "${status}"
}
trap cleanup EXIT

fail() {
    echo "UPGRADE TEST FAIL: $*" >&2
    dc logs --no-color --tail 40 >&2 || true
    exit 1
}

step() { echo "==> $*"; }

is_healthy() { curl -fsS "${BASE}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; }

wait_healthy() {
    local deadline=$((SECONDS + 120))
    until is_healthy; do
        [ "${SECONDS}" -lt "${deadline}" ] || fail "app not healthy within 120s"
        sleep 2
    done
}

env_tag() { sed -n 's/^CENTRY_TAG=//p' "${INSTALL}/.env"; }

running_image() { docker inspect --format '{{.Config.Image}}' centry; }

backups() { find "${DATA}/backups" -name '*.db' 2>/dev/null | wc -l | tr -d ' '; }

post_expense() { # transactionId
    local out
    out=$(curl -sS -u "${ADMIN_USER}:${ADMIN_PASSWORD}" -H 'Content-Type: application/json' -w '\n%{http_code}' \
        -d "{\"transactionId\":\"$1\",\"merchant\":\"Upgrade Mart\",\"amount\":12.34,\"category\":\"Misc\"}" \
        "${BASE}/api/v1/expenses")
    [ "$(printf '%s' "${out}" | tail -n1)" = 201 ] || fail "POST expense failed: ${out}"
    printf '%s' "${out}" | head -n1 | sed -n 's/.*"id":\([0-9]*\).*/\1/p'
}

expect_expense() { # id description
    local code
    code=$(curl -s -o /dev/null -w '%{http_code}' -u "${ADMIN_USER}:${ADMIN_PASSWORD}" "${BASE}/api/v1/expenses/$1")
    [ "${code}" = 200 ] || fail "expense $1 missing ($2): HTTP ${code}"
}

upgrade() { # tag [timeout]
    (cd "${INSTALL}" && CENTRY_HEALTH_TIMEOUT=${2:-120} ./scripts/upgrade.sh "$1")
}

step "prepare images and a local registry"
docker image inspect centry:smoke >/dev/null 2>&1 || docker build -q -t centry:smoke "${ROOT}"
docker rm -f "${REGISTRY_NAME}" >/dev/null 2>&1 || true
docker run -d --name "${REGISTRY_NAME}" -p "127.0.0.1:${REGISTRY_PORT}:5000" registry:2.8.3 >/dev/null
printf 'FROM centry:smoke\nENTRYPOINT ["sh","-c","echo garbage > /data/centry.db; echo garbage > /data/centry.db-wal; exit 1"]\n' \
    | docker build -q -t "${REPO}:bad" - >/dev/null
docker tag centry:smoke "${REPO}:good"
docker tag centry:smoke "${REPO}:good2"
for tag in good good2 bad; do
    docker push -q "${REPO}:${tag}" >/dev/null
done

step "start on tag good and record an expense"
dc up -d
wait_healthy
expense_id=$(post_expense upgrade-1)
expect_expense "${expense_id}" "before upgrade"
# Fourteen stale backups: a successful upgrade must prune back to the newest 14.
# shellcheck disable=SC2016 # the snippet runs in the container, so $n must not expand here
dc run --rm --no-deps -T --entrypoint sh app -c \
    'mkdir -p /data/backups && for n in 01 02 03 04 05 06 07 08 09 10 11 12 13 14; do : > /data/backups/centry-202001010000$n-old.db; done'

step "good -> good2 succeeds"
upgrade good2
[ "$(env_tag)" = good2 ] || fail ".env tag is '$(env_tag)', expected good2"
running_image | grep -q ':good2$' || fail "running image is $(running_image), expected tag good2"
wait_healthy
expect_expense "${expense_id}" "after good upgrade"
ls "${DATA}"/backups/centry-*-good.db >/dev/null 2>&1 || fail "no backup centry-<ts>-good.db in ${DATA}/backups"
[ "$(backups)" = 14 ] || fail "expected 14 backups after pruning, found $(backups)"

step "good2 -> bad fails and rolls back"
if upgrade bad 20; then fail "upgrade to a broken image reported success"; fi
[ "$(env_tag)" = good2 ] || fail ".env tag is '$(env_tag)' after rollback, expected good2"
wait_healthy
running_image | grep -q ':good2$' || fail "after rollback running image is $(running_image), expected tag good2"
expect_expense "${expense_id}" "after rollback (database restored)"
ls "${DATA}"/backups/centry-*-good2.db >/dev/null 2>&1 || fail "no backup centry-<ts>-good2.db after failed upgrade"

step "unpullable tag fails before anything changes"
before=$(backups)
if upgrade no-such-tag; then fail "upgrade to a missing tag reported success"; fi
[ "$(env_tag)" = good2 ] || fail ".env tag changed to '$(env_tag)' on a failed pull"
is_healthy || fail "app was stopped by a failed pull"
[ "$(backups)" = "${before}" ] || fail "a failed pull still took a backup"
expect_expense "${expense_id}" "after failed pull"

echo "UPGRADE TEST OK"
