#!/usr/bin/env bash
# Walks through ratekit end to end against a running stack and prints every request and answer.
#
#   docker compose up -d --build        (or: podman compose up -d --build)
#   scripts/demo.sh                     the usage flow: tariff, account, top-up, events, balance
#   scripts/demo.sh --invoice           also invoices last month (restarts ingest with a wide window)
#
# Every run uses a fresh account id, so the script can be repeated. Works with bash 3 (macOS).
set -euo pipefail

COMPOSE=${COMPOSE:-$(command -v docker >/dev/null 2>&1 && echo "docker compose" || echo "podman compose")}
SHOP=${SHOP_API_KEY:-local-shop-key}
OPERATOR=${OPERATOR_API_KEY:-local-operator-key}
READONLY=${READONLY_API_KEY:-local-readonly-key}
ACCOUNT="acc-$(date +%s)"
JSON='Content-Type: application/json'

step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
note() { printf '   %s\n' "$1"; }

# call KEY METHOD URL [BODY]: prints the request, the status and the body
call() {
    local key=$1 method=$2 url=$3 body=${4:-}
    local args=(-s -o /tmp/ratekit-demo.out -w '%{http_code}' -X "$method" "$url")
    [ -n "$key" ] && args+=(-H "X-Api-Key: $key")
    [ -n "$body" ] && args+=(-H "$JSON" -d "$body")
    local status
    status=$(curl "${args[@]}")
    printf '   %-4s %-55s -> %s %s\n' "$method" "${url#http://localhost}" "$status" "$(head -c 300 /tmp/ratekit-demo.out)"
}

event() { # event KEY ID METER QUANTITY OCCURRED_AT [ACCOUNT]
    call "$1" POST http://localhost:8081/v1/events \
        "{\"eventId\":\"$2\",\"accountId\":\"${6:-$ACCOUNT}\",\"meter\":\"$3\",\"quantity\":$4,\"occurredAt\":\"$5\"}"
}

wait_healthy() {
    local port
    for port in 8081 8082 8083; do
        for _ in $(seq 1 60); do
            curl -sf "http://localhost:$port/actuator/health" >/dev/null && continue 2
            sleep 2
        done
        echo "service on port $port is not healthy; start the stack first: $COMPOSE up -d --build" >&2
        exit 1
    done
}

step "0. The stack is up"
wait_healthy
note "ingest :8081, rating :8082, billing :8083 answer /actuator/health"

step "1. A tariff: 100 free SMS a month, then 0.05 each (SQL, because it starts on 1 January)"
$COMPOSE exec -T postgres psql -q -U ratekit -d ratekit < scripts/seed-demo.sql
call "$READONLY" GET "http://localhost:8082/v1/tariffs?meter=sms"

step "2. Open account $ACCOUNT and top it up"
call "$OPERATOR" POST http://localhost:8082/v1/accounts "{\"accountId\":\"$ACCOUNT\"}"
call "$OPERATOR" POST "http://localhost:8082/v1/accounts/$ACCOUNT/top-ups" '{"topUpId":"tu-1","amount":"100.00"}'
note "the same topUpId again is a safe retry: 200 and the balance does not change"
call "$OPERATOR" POST "http://localhost:8082/v1/accounts/$ACCOUNT/top-ups" '{"topUpId":"tu-1","amount":"100.00"}'

step "3. Usage that happens now"
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
event "$SHOP" e-1 sms 95 "$NOW"
event "$SHOP" e-2 sms 10 "$NOW"
note "the same eventId again is accepted by ingest but rated only once"
event "$SHOP" e-2 sms 10 "$NOW"

step "4. What the gates refuse"
note "no key: 401; a key without the scope: 403; a date next week: 422; a broken body: 400"
event "" e-x sms 1 "$NOW"
event "$READONLY" e-x sms 1 "$NOW"
event "$SHOP" e-x sms 1 "2030-01-01T00:00:00Z"
call "$SHOP" POST http://localhost:8081/v1/events '{"eventId":"","quantity":0}'
note "an unknown account is accepted at the door (202) and dead-lettered by rating"
event "$SHOP" e-ghost sms 1 "$NOW" "ghost-$ACCOUNT"

step "5. The result: e-1 is free, e-2 pays for the 5 SMS over the quota"
sleep 3
call "$READONLY" GET "http://localhost:8082/v1/accounts/$ACCOUNT"
$COMPOSE exec -T postgres psql -U ratekit -d ratekit \
    -c "SELECT event_id, quantity, amount FROM charges WHERE account_id = '$ACCOUNT' ORDER BY id;"
note "expected: two charges (0.0000 and 0.2500) and a balance of 99.7500"

if [ "${1:-}" != "--invoice" ]; then
    step "Done. Run with --invoice to also invoice last month."
    exit 0
fi

step "6. Invoice last month"
note "a month is invoiced only after it has ended, so the demo sends usage for last month;"
note "ingest normally refuses that (422), so it is restarted with a 62-day late-arrival window"
LATE_ARRIVAL_GRACE=P62D $COMPOSE up -d ingest >/dev/null 2>&1
wait_healthy
LAST=$(date -u -v1d -v-1m +%Y-%m 2>/dev/null || date -u -d "$(date -u +%Y-%m-01) -1 month" +%Y-%m)
event "$SHOP" e-3 sms 105 "$LAST-15T10:00:00Z"
note "billing waits until rating has rated last month's usage and the charges reached its own database"
for _ in $(seq 1 10); do
    status=$(curl -s -o /tmp/ratekit-demo.out -w '%{http_code}' -X POST http://localhost:8083/v1/invoice-runs \
        -H "X-Api-Key: $OPERATOR" -H "$JSON" -d "{\"period\":\"$LAST\"}")
    [ "$status" = 200 ] && break
    note "$status $(head -c 160 /tmp/ratekit-demo.out); trying again"
    sleep 3
done
printf '   POST /v1/invoice-runs -> %s %s\n' "$status" "$(cat /tmp/ratekit-demo.out)"
call "$READONLY" GET "http://localhost:8083/v1/invoices/$ACCOUNT?period=$LAST"
note "expected: one sms line of 105 units, total 0.2500; a second run creates nothing:"
call "$OPERATOR" POST http://localhost:8083/v1/invoice-runs "{\"period\":\"$LAST\"}"
$COMPOSE up -d ingest >/dev/null 2>&1
step "Done. ingest is back on its one-hour window."
