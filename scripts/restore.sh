#!/usr/bin/env bash
# Restore the Centry database from a file in <CENTRY_DATA>/backups, keeping the current one so the restore can be undone.
#
#   scripts/restore.sh <backup-file-name>      (run from the install dir, e.g. /opt/centry)
#
# The name is a bare file name like nightly-20260315.db (nightly backups) or centry-20260315T033000-v1.db (upgrade
# backups); see 'ls <CENTRY_DATA>/backups'. Steps: check the backup exists, stop the app, move the current database
# aside to backups/pre-restore-<stamp>.db, copy the backup into place, start, wait for health. If the restored app is
# not healthy the script says how to undo: restore the pre-restore file the same way.
#
# Exit codes: 0 healthy, 1 not healthy after the restore, 2 bad usage or no such backup (nothing was changed).
# Env: COMPOSE_FILES (compose -f args, default "-f docker-compose.yml"; add the Kafka override if you use it),
#      CENTRY_HEALTH_URL (default http://localhost:<CENTRY_PORT>/actuator/health),
#      CENTRY_HEALTH_TIMEOUT (seconds, default 120).
set -euo pipefail
umask 077

[ $# -eq 1 ] || { echo "usage: $0 <backup-file-name>" >&2; exit 2; }
BACKUP=$1
# A bare name only: no slash, so the argument cannot point outside backups/.
if ! [[ "${BACKUP}" =~ ^[A-Za-z0-9._-]+\.db$ ]]; then
    echo "invalid backup name '${BACKUP}': use a file name like nightly-20260315.db, without a path" >&2
    exit 2
fi

cd "$(dirname "$0")/.."
[ -f .env ] || { echo "no .env in $(pwd): run this from the install dir" >&2; exit 2; }

COMPOSE_FILES=${COMPOSE_FILES:--f docker-compose.yml}
HEALTH_TIMEOUT=${CENTRY_HEALTH_TIMEOUT:-120}

env_value() { sed -n "s/^$1=//p" .env | tail -n1; }

PORT=${CENTRY_PORT:-$(env_value CENTRY_PORT)}
HEALTH_URL=${CENTRY_HEALTH_URL:-http://localhost:${PORT:-8080}/actuator/health}

dc() {
    # shellcheck disable=SC2086 # COMPOSE_FILES is a list of -f arguments on purpose
    docker compose ${COMPOSE_FILES} "$@"
}

# Runs a shell snippet inside the app image with /data mounted: the files there belong to the container user.
data_sh() {
    dc run --rm --no-deps -T --entrypoint sh app -c "$@"
}

wait_healthy() {
    local deadline=$((SECONDS + HEALTH_TIMEOUT))
    until curl -fsS "${HEALTH_URL}" 2>/dev/null | grep -q '"status":"UP"'; do
        [ "${SECONDS}" -lt "${deadline}" ] || return 1
        sleep 2
    done
}

# Check before stopping anything, so a typo costs no downtime.
# shellcheck disable=SC2016 # the snippet runs in the container, so $1 must not expand here
if ! data_sh '[ -f "/data/backups/$1" ]' sh "${BACKUP}"; then
    echo "no backup '${BACKUP}' in backups/. Nothing was changed. Available:" >&2
    # shellcheck disable=SC2016
    data_sh 'ls -1 /data/backups 2>/dev/null | grep "\.db$" || echo "  (none)"' sh >&2 || true
    exit 2
fi

echo "Stopping the app"
dc stop -t 30 app

STAMP=$(date -u +%Y%m%dT%H%M%S)
SAFETY="pre-restore-${STAMP}.db"
echo "Restoring backups/${BACKUP}; the current database goes to backups/${SAFETY}"
# $1 = backup to restore, $2 = name for the replaced database. -wal/-shm travel with their database, and stale ones
# must not stay behind: SQLite would replay a log that does not belong to the restored file.
# shellcheck disable=SC2016 # the snippet runs in the container, so $1/$2 must not expand here
data_sh '
    set -e
    mkdir -p /data/backups
    for ext in "" -wal -shm; do
        [ -f "/data/centry.db$ext" ] && mv "/data/centry.db$ext" "/data/backups/$2$ext"
    done
    for ext in "" -wal -shm; do
        [ -f "/data/backups/$1$ext" ] && cp -p "/data/backups/$1$ext" "/data/centry.db$ext"
    done
    exit 0' sh "${BACKUP}" "${SAFETY}"

echo "Starting the app"
dc up -d app

if wait_healthy; then
    echo "Centry is healthy on the data of backups/${BACKUP}. The replaced database is backups/${SAFETY}."
    exit 0
fi

echo "Centry was not healthy within ${HEALTH_TIMEOUT}s after the restore." >&2
dc logs --no-color --tail 30 app >&2 || true
echo "To undo, restore the database you had before:  scripts/restore.sh ${SAFETY}" >&2
exit 1
