#!/usr/bin/env bash
# Load test for the whole stack: seeds data, drives ingest with k6, then watches rating catch up.
# The stack must be up (compose up -d). With Podman:
#   COMPOSE="podman compose" CONTAINER=podman load/run.sh
# Variables: RATES (target requests per second per stage), STAGE_SECONDS, DASHBOARD=1 for k6's live
# web dashboard on http://localhost:5665, NETWORK (compose network, default ratekit_default).
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE=${COMPOSE:-docker compose}
CONTAINER=${CONTAINER:-docker}
NETWORK=${NETWORK:-ratekit_default}
RATES=${RATES:-200,400,800,1200,1600}
STAGE_SECONDS=${STAGE_SECONDS:-10}
RUN=${RUN:-run-$(date +%s)}

psql_q() { $COMPOSE exec -T postgres psql -U ratekit -d ratekit -tA "$@" 2>/dev/null; }

echo "== seeding 200 accounts and one tariff (this empties the charge tables)"
$COMPOSE exec -T postgres psql -U ratekit -d ratekit < load/seed-load.sql > /dev/null

dashboard=()   # may stay empty; the ${...+...} form below keeps old bash (macOS) happy under set -u
if [ "${DASHBOARD:-0}" = "1" ]; then
  dashboard=(-p 5665:5665 -e K6_WEB_DASHBOARD=true -e K6_WEB_DASHBOARD_HOST=0.0.0.0 -e K6_WEB_DASHBOARD_PORT=5665)
  echo "== live dashboard: http://localhost:5665 (available while k6 runs)"
fi

echo "== k6: rates $RATES, ${STAGE_SECONDS}s per stage"
start=$(date +%s)
$CONTAINER run --rm -i --network "$NETWORK" --memory 300m ${dashboard[@]+"${dashboard[@]}"} \
  -e TARGET=http://ingest:8081 -e RUN="$RUN" -e STAGE_SECONDS="$STAGE_SECONDS" -e RATES="$RATES" \
  docker.io/grafana/k6:2.3.0 run - < load/k6/ingest.js | tee /tmp/ratekit-k6.log
end=$(date +%s)

accepted=$(grep -E "^ *events_accepted" /tmp/ratekit-k6.log | grep -oE "[0-9]+ " | head -1 | tr -d ' ')
echo "== waiting for rating to process the $accepted accepted events"
previous=-1; same=0
while true; do
  charges=$(psql_q -c "select count(*) from charges")
  [ "$charges" -ge "$accepted" ] && break
  if [ "$charges" = "$previous" ]; then same=$((same + 1)); else same=0; fi
  [ "$same" -ge 6 ] && { echo "   stalled at $charges charges"; break; }
  previous=$charges; sleep 5
done
echo "== done: $charges charges, $(( $(date +%s) - end ))s after the load ended (load lasted $((end - start))s)"

echo "== rating metrics"
curl -s localhost:8082/actuator/prometheus | awk '
  /^ratekit_rating_duration_seconds_count/ {c=$2}
  /^ratekit_rating_duration_seconds_sum/   {s=$2}
  /^kafka_consumer_fetch_manager_records_lag_max/ {if ($2+0 > lag) lag=$2+0}
  END {printf "   events timed: %d, mean per event: %.2f ms, max consumer lag: %d records\n", c, s/c*1000, lag}'
