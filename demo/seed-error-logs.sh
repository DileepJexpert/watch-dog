#!/usr/bin/env bash
# Inject fake ERROR log entries into Elasticsearch so WATCHDOG's
# ElasticsearchConnector picks them up on its next poll (every 30s).
#
# Usage:   ./demo/seed-error-logs.sh [service] [count]
# Default: service=payments-svc, count=15
#
# After this runs, watch for:
#   - GET /api/dashboard/incidents/active  → new OPEN incident within ~30s
#   - The AI Copilot can answer "what just happened to <service>?"

set -euo pipefail
ES_URL="${ES_URL:-http://localhost:9200}"
SERVICE="${1:-payments-svc}"
COUNT="${2:-15}"

# Today's index (matches ES's daily rollover convention; index-pattern is "logs-*").
TODAY=$(date -u +%Y.%m.%d)
INDEX="logs-${TODAY}"

echo "[seed] injecting ${COUNT} ERROR docs into ${ES_URL}/${INDEX} for service=${SERVICE}"

# Build a bulk payload.
TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT

ERR_MSGS=(
  "JdbcSQLException: Connection is not available, request timed out after 30000ms"
  "HikariPool-1 - Connection is not available, request timed out (active=20, idle=0)"
  "java.sql.SQLException: Cannot get a connection, pool error Timeout waiting for idle object"
  "OutOfMemoryError: Java heap space"
  "Connection refused: payments-db:5432"
  "java.net.SocketTimeoutException: Read timed out"
)

for i in $(seq 1 "$COUNT"); do
  MSG="${ERR_MSGS[$((RANDOM % ${#ERR_MSGS[@]}))]}"
  TS=$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)
  echo "{\"index\":{\"_index\":\"${INDEX}\"}}" >> "$TMP"
  printf '{"@timestamp":"%s","service":{"name":"%s"},"log":{"level":"ERROR"},"message":"%s","error":{"type":"java.sql.SQLException","message":"%s"}}\n' \
    "$TS" "$SERVICE" "$MSG" "$MSG" >> "$TMP"
done

curl -sS -H 'Content-Type: application/x-ndjson' \
  -X POST "${ES_URL}/_bulk" --data-binary @"$TMP" \
  | python3 -c 'import sys, json; r = json.load(sys.stdin); print("[seed] errors=", r.get("errors"), "items=", len(r.get("items", [])))'

echo "[seed] done — WATCHDOG will pick these up within ~30s (poll-interval-seconds)"
