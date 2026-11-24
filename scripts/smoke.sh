#!/usr/bin/env bash
# End-to-end smoke test: checks that telemetry from the simulator flows through
# gRPC -> Kafka -> consumers -> Postgres and shows up in metrics and GraphQL.
#
# Usage: ./scripts/smoke.sh            (expects services + simulator already running)
#        TIMEOUT=180 ./scripts/smoke.sh
set -euo pipefail

TIMEOUT="${TIMEOUT:-120}"
INGESTION="${INGESTION:-http://localhost:8081}"
HEALTH="${HEALTH:-http://localhost:8082}"
SAFETY="${SAFETY:-http://localhost:8083}"
PROMETHEUS="${PROMETHEUS:-http://localhost:9091}"

green() { printf '\033[32m✔ %s\033[0m\n' "$1"; }
red()   { printf '\033[31m✘ %s\033[0m\n' "$1"; }
wait_for() { # wait_for <description> <command...>
  local desc="$1"; shift
  local deadline=$((SECONDS + TIMEOUT))
  until "$@" >/dev/null 2>&1; do
    if (( SECONDS > deadline )); then red "$desc (timeout ${TIMEOUT}s)"; exit 1; fi
    sleep 3
  done
  green "$desc"
}

# Value of a Prometheus-format metric line exposed by a service, summed across tags.
metric_sum() { # metric_sum <url> <metric name> <label filter>
  curl -fsS "$1/actuator/prometheus" | grep "^$2{" | grep -- "$3" | awk '{s += $NF} END {printf "%d", s}'
}
metric_positive() { (( $(metric_sum "$@") > 0 )); }

graphql_has_devices() {
  curl -fsS "$HEALTH/graphql" -H 'Content-Type: application/json' \
    -d '{"query":"{ fleetHealthSummary { totalDevices } }"}' \
  | grep -Eq '"totalDevices":[1-9]'
}

degraded_devices() {
  curl -fsS "$HEALTH/graphql" -H 'Content-Type: application/json' \
    -d '{"query":"{ devices(state: DEGRADED) { id } }"}' \
  | grep -q '"id"'
}

echo "FleetCam smoke test (timeout ${TIMEOUT}s per check)"
wait_for "ingestion-service healthy"       curl -fsS "$INGESTION/actuator/health"
wait_for "device-health-service healthy"   curl -fsS "$HEALTH/actuator/health"
wait_for "safety-event-processor healthy"  curl -fsS "$SAFETY/actuator/health"
wait_for "gateway accepting telemetry"     metric_positive "$INGESTION" fleetcam_ingestion_messages_total 'outcome="accepted"'
wait_for "devices registered (GraphQL)"    graphql_has_devices
wait_for "safety events stored"            metric_positive "$SAFETY" fleetcam_safety_events_total 'outcome="stored"'
wait_for "injected faults detected"        degraded_devices
wait_for "Prometheus scraping services"    sh -c "curl -fsS '$PROMETHEUS/api/v1/targets' | grep -q '\"health\":\"up\"'"

echo
green "Pipeline OK: simulator -> gRPC -> Kafka -> consumers -> Postgres -> GraphQL/metrics"
echo "  Grafana:  http://localhost:3000"
echo "  GraphiQL: $HEALTH/graphiql"
