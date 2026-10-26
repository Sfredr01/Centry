#!/usr/bin/env bash
# Walking-skeleton smoke test (docs/ROADMAP.md section 6): brings up the real compose stack with a local ntfy
# container and checks health, auth, budgets, alert de-duplication, persistence and memory end to end.
#
# Env: CENTRY_PORT (host port of the app, default 8080), CENTRY_NTFY_PORT (default 18080),
#      COMPOSE_FILES (compose -f args, default docker-compose.yml + docker-compose.smoke.yml),
#      KEEP=1 (leave the stack and temp dir running for debugging).
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
PORT=${CENTRY_PORT:-8080}
NTFY_PORT=${CENTRY_NTFY_PORT:-18080}
BASE="http://localhost:${PORT}"
NTFY="http://127.0.0.1:${NTFY_PORT}/centry-smoke"
COMPOSE_FILES=${COMPOSE_FILES:--f ${ROOT}/docker-compose.yml -f ${ROOT}/docker-compose.smoke.yml}
ADMIN_USER=admin
ADMIN_PASSWORD=smoke-test-password-123
RSS_LIMIT_MIB=400

WORK=$(mktemp -d "${TMPDIR:-/tmp}/centry-smoke.XXXXXX")
mkdir -p "${WORK}/data"
# The container runs as uid 10001; a world-writable dir works without root on the host.
chmod 777 "${WORK}/data"
cat > "${WORK}/.env" <<ENV
CENTRY_ADMIN_USERNAME=${ADMIN_USER}
CENTRY_ADMIN_PASSWORD=${ADMIN_PASSWORD}
CENTRY_TAG=smoke
CENTRY_IMAGE=centry
CENTRY_DATA=${WORK}/data
CENTRY_PORT=${PORT}
CENTRY_NTFY_PORT=${NTFY_PORT}
ENV
export CENTRY_BUILD_CONTEXT="${ROOT}"

dc() {
    # shellcheck disable=SC2086 # COMPOSE_FILES is a list of -f arguments on purpose
    docker compose --project-directory "${WORK}" -p centry-smoke ${COMPOSE_FILES} "$@"
}

cleanup() {
    status=$?
    if [ "${KEEP:-0}" = 1 ]; then
        echo "KEEP=1: stack left running, temp dir ${WORK}"
        exit "${status}"
    fi
    dc down -v --remove-orphans >/dev/null 2>&1 || true
    rm -rf "${WORK}" 2>/dev/null || true
    exit "${status}"
}
trap cleanup EXIT

fail() {
    echo "SMOKE FAIL: $*" >&2
    dc logs --no-color --tail 40 >&2 || true
    exit 1
}

step() { echo "==> $*"; }

http_code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

wait_healthy() {
    local deadline=$((SECONDS + 120))
    until curl -fsS "${BASE}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; do
        [ "${SECONDS}" -lt "${deadline}" ] || fail "app not healthy within 120s"
        sleep 2
    done
}

api() { # method path [json-body]
    local method=$1 path=$2 body=${3:-}
    if [ -n "${body}" ]; then
        curl -sS -u "${ADMIN_USER}:${ADMIN_PASSWORD}" -X "${method}" -H 'Content-Type: application/json' \
            -d "${body}" -w '\n%{http_code}' "${BASE}${path}"
    else
        curl -sS -u "${ADMIN_USER}:${ADMIN_PASSWORD}" -X "${method}" -w '\n%{http_code}' "${BASE}${path}"
    fi
}

# Posts an expense, prints the new expense id.
post_expense() { # transactionId amount
    local out code
    out=$(api POST /api/v1/expenses \
        "{\"transactionId\":\"$1\",\"merchant\":\"Smoke Mart\",\"amount\":$2,\"category\":\"Groceries\"}")
    code=$(printf '%s' "${out}" | tail -n1)
    [ "${code}" = 201 ] || fail "POST expense $1 returned ${code}: ${out}"
    printf '%s' "${out}" | head -n1 | sed -n 's/.*"id":\([0-9]*\).*/\1/p'
}

# Number of ntfy messages whose text contains $1.
pushes() { { curl -fsS "${NTFY}/json?poll=1&since=all" || true; } | grep -c "$1" || true; }

expect_pushes() { # pattern count description
    local deadline=$((SECONDS + 20)) n
    while :; do
        n=$(pushes "$1")
        [ "${n}" = "$2" ] && return 0
        [ "${SECONDS}" -lt "${deadline}" ] || fail "$3: expected $2 push(es) matching '$1', saw ${n}"
        sleep 1
    done
}

# Negative check: the count must stay put after a grace period for a late push.
expect_pushes_stable() {
    sleep 4
    expect_pushes "$@"
}

step "start stack"
dc up -d --build
wait_healthy

step "health shows no details, metrics are not exposed"
health=$(curl -fsS "${BASE}/actuator/health")
printf '%s' "${health}" | grep -q '"status":"UP"' || fail "health is not UP: ${health}"
if printf '%s' "${health}" | grep -Eq 'components|details|groups'; then
    fail "health leaks detail: ${health}"
fi
for path in /actuator/prometheus /actuator/metrics; do
    code=$(http_code "${BASE}${path}")
    case "${code}" in 404 | 401) ;; *) fail "${path} returned ${code}, expected 404 or 401" ;; esac
done

step "form login with CSRF"
jar="${WORK}/cookies"
csrf=$(curl -fsS -c "${jar}" "${BASE}/login" | grep -o 'name="_csrf" value="[^"]*"' | head -n1 | sed 's/.*value="//;s/"$//')
[ -n "${csrf}" ] || fail "no CSRF token on /login"
code=$(http_code -b "${jar}" -c "${jar}" --data-urlencode "username=${ADMIN_USER}" \
    --data-urlencode "password=${ADMIN_PASSWORD}" --data-urlencode "_csrf=${csrf}" "${BASE}/login")
[ "${code}" = 302 ] || fail "login POST returned ${code}"
code=$(http_code -b "${jar}" "${BASE}/budgets")
[ "${code}" = 200 ] || fail "/budgets after login returned ${code} (not signed in)"

step "budget and alerts"
out=$(api PUT /api/v1/budgets/Groceries '{"monthlyLimit":100}')
[ "$(printf '%s' "${out}" | tail -n1)" = 200 ] || fail "PUT budget failed: ${out}"
first_id=$(post_expense smoke-1 85)
expect_pushes "warning threshold" 1 "after \$85"
post_expense smoke-2 1 >/dev/null
expect_pushes_stable "warning threshold" 1 "after \$1 (duplicate WARN)"
post_expense smoke-3 20 >/dev/null
expect_pushes "monthly limit" 1 "after \$20"
expect_pushes "warning threshold" 1 "WARN count after limit"

step "restart does not replay alerts"
dc restart app
wait_healthy
post_expense smoke-4 1 >/dev/null
expect_pushes_stable "warning threshold" 1 "WARN after restart"
expect_pushes_stable "monthly limit" 1 "EXCEEDED after restart"

step "data survives down and up"
dc down
dc up -d
wait_healthy
out=$(api GET "/api/v1/expenses/${first_id}")
[ "$(printf '%s' "${out}" | tail -n1)" = 200 ] || fail "expense ${first_id} lost after down/up: ${out}"

step "memory"
cid=$(dc ps -q app)
usage=$(docker stats --no-stream --format '{{.MemUsage}}' "${cid}" | awk '{print $1}')
mib=$(printf '%s' "${usage}" | awk '{
    v = $0 + 0
    if ($0 ~ /GiB$/) v *= 1024; else if ($0 ~ /KiB$/) v /= 1024; else if ($0 ~ /MiB$/) v = v; else v /= 1048576
    printf "%d", v }')
echo "container memory: ${usage} (limit ${RSS_LIMIT_MIB} MiB)"
[ "${mib}" -lt "${RSS_LIMIT_MIB}" ] || fail "memory ${usage} is not under ${RSS_LIMIT_MIB} MiB"

echo "SMOKE OK (memory ${usage})"
