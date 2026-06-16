#!/usr/bin/env bash
# End-to-end status probe for a local SENTINEL stack.
# Each section is independent — if one component isn't up, the others still print.

set -u
API="${SENTINEL_API:-http://localhost:8080}"
ES="${ES_URL:-http://localhost:9201}"
JAEGER="${JAEGER_URL:-http://localhost:16687}"
GRAFANA="${GRAFANA_URL:-http://localhost:3001}"

bar() { printf '\n── %s ──\n' "$1"; }
hit() { local code; code=$(curl -s -o /dev/null -w '%{http_code}' "$1" || echo 000); printf '%-50s  HTTP %s\n' "$1" "$code"; }

bar "Infra reachability"
hit "${ES}/_cluster/health"
hit "${JAEGER}/api/services"
hit "${GRAFANA}/api/health"
hit "${API}/actuator/health"

bar "Backend dashboard"
curl -s "${API}/api/dashboard/stats" | python3 -m json.tool 2>/dev/null || echo "(dashboard not reachable)"

bar "Open incidents"
curl -s "${API}/api/dashboard/incidents/active" \
  | python3 -c 'import sys, json
data = json.load(sys.stdin)
if not data:
    print("(no open incidents)")
for i in data:
    print(f"  [{i[\"severity\"]}] {i[\"serviceName\"]:25} rule={i.get(\"correlationRule\",\"-\")}  title={i[\"title\"]}")' 2>/dev/null

bar "AI Copilot status"
curl -s "${API}/api/agent/status" | python3 -m json.tool 2>/dev/null || echo "(agent endpoint not reachable)"

bar "Recent ERROR logs visible to SENTINEL (last 5 min)"
curl -s -H 'Content-Type: application/json' \
  -X POST "${ES}/logs-*/_search" \
  -d '{"query":{"bool":{"must":[{"term":{"log.level":"ERROR"}},{"range":{"@timestamp":{"gte":"now-5m"}}}]}},"size":0,"aggs":{"by_service":{"terms":{"field":"service.name.keyword","size":10}}}}' \
  | python3 -c 'import sys, json
r = json.load(sys.stdin)
buckets = r.get("aggregations",{}).get("by_service",{}).get("buckets",[])
total = r.get("hits",{}).get("total",{}).get("value",0)
print(f"  total ERRORs in last 5 min: {total}")
for b in buckets:
    print(f"    {b[\"key\"]:30} {b[\"doc_count\"]} entries")' 2>/dev/null \
  || echo "(elasticsearch not reachable)"

echo
