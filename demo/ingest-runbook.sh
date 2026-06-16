#!/usr/bin/env bash
# Push a runbook into the AI Copilot's knowledge base.
# Usage: ./demo/ingest-runbook.sh

set -euo pipefail
API="${SENTINEL_API:-http://localhost:8080}"

cat <<'EOF' | curl -sS -X POST "${API}/api/agent/knowledge" \
  -H 'Content-Type: application/json' --data-binary @- | python3 -m json.tool
{
  "title": "Payments — DB connection pool exhaustion runbook",
  "content": "Symptom: HTTP 5xx spike, latency P95 climbs > 2s, logs show 'JdbcSQLException: Connection is not available'.\n\nCause: Hikari pool (max-pool-size=20) exhausted. Common triggers: a long-running batch job holding connections, an upstream DB slowdown causing connection acquisition timeouts, or a leak from missing try-with-resources.\n\nDiagnosis:\n  1. Check active txn count: SELECT count(*) FROM pg_stat_activity WHERE datname='payments' AND state='active';\n  2. Identify long-running queries: SELECT pid, now()-xact_start AS duration, query FROM pg_stat_activity WHERE state='active' ORDER BY duration DESC LIMIT 10;\n  3. Check pool stats via /actuator/metrics/hikaricp.connections.active vs hikaricp.connections.max.\n\nRemediation:\n  - Short term: cancel offending long-running query (pg_cancel_backend(pid))\n  - Medium term: bump max-pool-size from 20 to 40 while investigating\n  - Long term: add a connection leak detection threshold (Hikari leakDetectionThreshold=30000)\n\nAvoid: blindly restarting pods — that papers over the leak and resets the timer."
}
EOF
