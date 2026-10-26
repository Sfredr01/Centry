#!/usr/bin/env bash
# Tests scripts/restore.sh against a real compose stack:
#   1. a restore brings back the data of the chosen backup (expense A present, B recorded later gone) and keeps
#      the replaced database as backups/pre-restore-<stamp>.db, which holds B (so a wrong restore is undoable);
#   2. bad file names (path traversal, wrong extension) and a missing backup exit 2 and change nothing;
#   3. no argument exits 2.
#
# Needs the image centry:smoke (built by scripts/smoke.sh; built here if missing).
# Env: CENTRY_PORT (default 8080), KEEP=1 to keep the stack and temp dir.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
PORT=${CENTRY_PORT:-8080}
BASE="http://localhost:${PORT}"
ADMIN_USER="admin"
ADMIN_PASSWORD=restore-test-password-123
# A unique project and container name, so this can run next to another Centry stack.
PROJECT=${COMPOSE_PROJECT_NAME:-centry-restore-test}

WORK=$(mktemp -d "${TMPDIR:-/tmp}/centry-restore-test.XXXXXX")
INSTALL="${WORK}/install"
DATA="${WORK}/data"
mkdir -p "${INSTALL}/scripts" "${DATA}"
# The container runs as uid 10001; a world-writable dir works without root on the host.
chmod 777 "${DATA}"
cp "${ROOT}/docker-compose.yml" "${INSTALL}/"
cp "${ROOT}/scripts/restore.sh" "${INSTALL}/scripts/restore.sh" 2>/dev/null || true
printf 'services:\n  app:\n    container_name: %s\n' "${PROJECT}" > "${INSTALL}/docker-compose.test.yml"
cat > "${INSTALL}/.env" <<ENV
CENTRY_ADMIN_USERNAME=${ADMIN_USER}
CENTRY_ADMIN_PASSWORD=${ADMIN_PASSWORD}
CENTRY_TAG=smoke
CENTRY_IMAGE=centry
CENTRY_DATA=${DATA}
CENTRY_PORT=${PORT}
ENV
export COMPOSE_PROJECT_NAME="${PROJECT}"
export COMPOSE_FILES="-f docker-compose.yml -f docker-compose.test.yml"

dc() { (cd "${INSTALL}" && docker compose -f docker-compose.yml -f docker-compose.test.yml "$@"); }

cleanup() {
    status=$?
    if [ "${KEEP:-0}" = 1 ]; then
        echo "KEEP=1: stack left running, temp dir ${WORK}"
        exit "${status}"
    fi
    dc down -v --remove-orphans >/dev/null 2>&1 || true
    # Files written by the container belong to uid 10001: remove them from inside a container.
    docker run --rm -v "${DATA}:/data" --entrypoint sh centry:smoke -c 'rm -rf /data/* /data/.[!.]*' >/dev/null 2>&1 || true
    rm -rf "${WORK}" 2>/dev/null || true
    exit "${status}"
}
trap cleanup EXIT

fail() {
    echo "RESTORE TEST FAIL: $*" >&2
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

post_expense() { # transactionId
    local out
    out=$(curl -sS -u "${ADMIN_USER}:${ADMIN_PASSWORD}" -H 'Content-Type: application/json' -w '\n%{http_code}' \
        -d "{\"transactionId\":\"$1\",\"merchant\":\"Restore Mart\",\"amount\":12.34,\"category\":\"Misc\"}" \
        "${BASE}/api/v1/expenses")
    [ "$(printf '%s' "${out}" | tail -n1)" = 201 ] || fail "POST expense failed: ${out}"
    printf '%s' "${out}" | head -n1 | sed -n 's/.*"id":\([0-9]*\).*/\1/p'
}

expense_status() { # id -> HTTP status
    curl -s -o /dev/null -w '%{http_code}' -u "${ADMIN_USER}:${ADMIN_PASSWORD}" "${BASE}/api/v1/expenses/$1"
}

restore() { # args for restore.sh; prints its output, returns its exit code
    (cd "${INSTALL}" && CENTRY_HEALTH_TIMEOUT=120 ./scripts/restore.sh "$@")
}

step "prepare the image"
docker image inspect centry:smoke >/dev/null 2>&1 || docker build -q -t centry:smoke "${ROOT}"

step "start, record expense A, take a backup"
dc up -d
wait_healthy
id_a=$(post_expense restore-a)
# A clean stop folds the WAL into centry.db, so copying that one file is a complete backup.
dc stop -t 30 app
# shellcheck disable=SC2016 # the snippet runs in the container
dc run --rm --no-deps -T --entrypoint sh app -c 'mkdir -p /data/backups && cp /data/centry.db /data/backups/nightly-20260101.db'
dc up -d
wait_healthy
id_b=$(post_expense restore-b)
[ "$(expense_status "${id_b}")" = 200 ] || fail "expense B missing before the restore"

step "bad arguments exit 2 and change nothing"
for bad in '../etc/passwd' 'a/b.db' 'nightly-20260101.sql' '.db' 'sp ace.db'; do
    set +e
    restore "${bad}" >/dev/null 2>&1
    code=$?
    set -e
    [ "${code}" = 2 ] || fail "restore '${bad}' exited ${code}, expected 2"
done
set +e
restore >/dev/null 2>&1
code=$?
restore nightly-29990101.db >/dev/null 2>&1
missing=$?
set -e
[ "${code}" = 2 ] || fail "restore without argument exited ${code}, expected 2"
[ "${missing}" = 2 ] || fail "restore of a missing backup exited ${missing}, expected 2"
is_healthy || fail "a rejected restore stopped the app"
[ "$(expense_status "${id_b}")" = 200 ] || fail "a rejected restore changed the data"

step "restore the backup"
restore nightly-20260101.db || fail "restore.sh failed"
wait_healthy
[ "$(expense_status "${id_a}")" = 200 ] || fail "expense A missing after the restore"
[ "$(expense_status "${id_b}")" = 404 ] || fail "expense B still present after the restore"
ls "${DATA}"/backups/pre-restore-*.db >/dev/null 2>&1 || fail "no pre-restore-<stamp>.db in ${DATA}/backups"
[ -f "${DATA}/backups/nightly-20260101.db" ] || fail "the chosen backup was consumed instead of copied"

echo "RESTORE TEST OK"
