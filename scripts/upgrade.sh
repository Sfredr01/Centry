#!/usr/bin/env bash
# Upgrade Centry to a pinned image tag, with a backup and an automatic rollback.
#
#   scripts/upgrade.sh <new-tag>      (run from the install dir, e.g. /opt/centry)
#
# Steps: pull the new tag first (fail fast, nothing changed), stop the app, back up the database, pin the new tag
# in .env, start, wait for health. If the app does not become healthy in time, the backup and the old tag come
# back and the script exits non-zero. Backups live in <CENTRY_DATA>/backups, the newest 14 are kept.
#
# Env: COMPOSE_FILES (compose -f args, default "-f docker-compose.yml"; add the Kafka override if you use it),
#      CENTRY_HEALTH_URL (default http://localhost:<CENTRY_PORT>/actuator/health),
#      CENTRY_HEALTH_TIMEOUT (seconds, default 120).
set -euo pipefail
umask 077

KEEP_BACKUPS=14

[ $# -eq 1 ] || { echo "usage: $0 <new-tag>" >&2; exit 2; }
NEW_TAG=$1
case "${NEW_TAG}" in
    '' | *[!A-Za-z0-9_.-]*) echo "invalid tag '${NEW_TAG}': use letters, digits, '.', '_' and '-'" >&2; exit 2 ;;
esac

cd "$(dirname "$0")/.."
[ -f .env ] || { echo "no .env in $(pwd): run this from the install dir" >&2; exit 2; }

COMPOSE_FILES=${COMPOSE_FILES:--f docker-compose.yml}
HEALTH_TIMEOUT=${CENTRY_HEALTH_TIMEOUT:-120}

env_value() { sed -n "s/^$1=//p" .env | tail -n1; }

OLD_TAG=$(env_value CENTRY_TAG)
[ -n "${OLD_TAG}" ] || { echo "CENTRY_TAG is not set in .env" >&2; exit 2; }
PORT=${CENTRY_PORT:-$(env_value CENTRY_PORT)}
HEALTH_URL=${CENTRY_HEALTH_URL:-http://localhost:${PORT:-8080}/actuator/health}

dc() {
    # shellcheck disable=SC2086 # COMPOSE_FILES is a list of -f arguments on purpose
    docker compose ${COMPOSE_FILES} "$@"
}

set_tag() {
    awk -v tag="$1" '/^CENTRY_TAG=/ { print "CENTRY_TAG=" tag; next } { print }' .env > .env.new
    cat .env.new > .env
    rm -f .env.new
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

echo "Upgrading Centry ${OLD_TAG} -> ${NEW_TAG}"

echo "Pulling ${NEW_TAG} (nothing is stopped yet)"
if ! CENTRY_TAG=${NEW_TAG} dc pull app; then
    echo "Pull of tag ${NEW_TAG} failed. Nothing was changed; still on ${OLD_TAG}." >&2
    exit 1
fi

echo "Stopping the app"
dc stop -t 30 app

STAMP=$(date -u +%Y%m%dT%H%M%S)
BACKUP="centry-${STAMP}-${OLD_TAG}.db"
echo "Backing up the database to backups/${BACKUP}"
# $1 = backup name, $2 = number of backups to keep. The -wal/-shm files are copied when present.
# shellcheck disable=SC2016 # the snippet runs in the container, so $1/$2 must not expand here
data_sh '
    if [ ! -f /data/centry.db ]; then echo "no database yet, nothing to back up"; exit 0; fi
    mkdir -p /data/backups
    for ext in "" -wal -shm; do
        [ -f "/data/centry.db$ext" ] && cp -p "/data/centry.db$ext" "/data/backups/$1$ext"
    done
    ls -1 /data/backups/centry-*.db | sort | head -n -"$2" | while read -r old; do
        rm -f "$old" "$old-wal" "$old-shm"
    done
    exit 0' sh "${BACKUP}" "${KEEP_BACKUPS}"

echo "Pinning CENTRY_TAG=${NEW_TAG} in .env and starting"
set_tag "${NEW_TAG}"
dc up -d app

if wait_healthy; then
    echo "Centry ${NEW_TAG} is healthy. Backup: backups/${BACKUP} (previous tag ${OLD_TAG})."
    exit 0
fi

echo "Centry ${NEW_TAG} was not healthy within ${HEALTH_TIMEOUT}s. Rolling back to ${OLD_TAG}." >&2
dc logs --no-color --tail 30 app >&2 || true
dc stop -t 30 app || true
set_tag "${OLD_TAG}"
# Remove stale -wal/-shm first so SQLite cannot replay a log that does not belong to the restored database.
# shellcheck disable=SC2016 # the snippet runs in the container, so $1 must not expand here
data_sh '
    [ -f "/data/backups/$1" ] || { echo "no backup $1 (there was no database before): database left as is" >&2; exit 0; }
    rm -f /data/centry.db /data/centry.db-wal /data/centry.db-shm
    for ext in "" -wal -shm; do
        [ -f "/data/backups/$1$ext" ] && cp -p "/data/backups/$1$ext" "/data/centry.db$ext"
    done
    exit 0' sh "${BACKUP}"
dc up -d app
if wait_healthy; then
    echo "Rolled back: running ${OLD_TAG} again with the database from backups/${BACKUP}." >&2
    echo "Upgrade to ${NEW_TAG} FAILED." >&2
    exit 1
fi
echo "ROLLBACK FAILED: ${OLD_TAG} is not healthy either. Backup: backups/${BACKUP}. Check 'docker compose logs app'." >&2
exit 3
