#!/usr/bin/env bash
# SENTINEL - one-shot log generation + verification.
#
# Hits every POST endpoint on sample-app with deliberately-bad payloads,
# optionally runs the sample-producer for a burst of mixed errors, waits
# for the ES poll cycle, then prints what landed.
#
# Usage:
#   ./test-logs.sh                                    # defaults: 5 cycles
#   CYCLES=10 ./test-logs.sh                          # 10 cycles
#   WITH_PRODUCER=1 ./test-logs.sh                    # also burst the producer
#   SERVICE=loan-svc-fresh WITH_PRODUCER=1 ./test-logs.sh

set -uo pipefail

SAMPLE_APP_URL="${SAMPLE_APP_URL:-http://localhost:1881}"
SENTINEL_URL="${SENTINEL_URL:-http://localhost:8080}"
ES_URL="${ES_URL:-http://localhost:9201}"
CYCLES="${CYCLES:-5}"
WITH_PRODUCER="${WITH_PRODUCER:-0}"
SERVICE="${SERVICE:-test-svc-$(date +%H%M%S)}"
PRODUCER_SECONDS="${PRODUCER_SECONDS:-60}"
PRODUCER_RATE="${PRODUCER_RATE:-5}"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; RED='\033[0;31m'; GRAY='\033[1;30m'; NC='\033[0m'

echo ""
echo -e "${CYAN}=== SENTINEL log generator ===${NC}"
echo "  sample-app : $SAMPLE_APP_URL"
echo "  sentinel   : $SENTINEL_URL"
echo "  ES         : $ES_URL"
echo "  cycles     : $CYCLES (each cycle hits all 8 payload variants)"
echo "  producer   : $( [ "$WITH_PRODUCER" = "1" ] && echo "yes, ${PRODUCER_SECONDS}s @ ${PRODUCER_RATE}/s, service=${SERVICE}" || echo "no" )"
echo ""

# -------------------------------------------------------------------------- #
# Pre-flight
# -------------------------------------------------------------------------- #
check() {
    local name=$1 url=$2
    if curl -sf --max-time 3 "$url" >/dev/null 2>&1; then
        echo -e "  ${GREEN}[OK]${NC}   $name reachable at $url"
        return 0
    else
        echo -e "  ${YELLOW}[WARN]${NC} $name NOT reachable at $url"
        return 1
    fi
}

SAMPLE_OK=1; SENTINEL_OK=1; ES_OK=1
check 'sample-app' "$SAMPLE_APP_URL/actuator/health" || SAMPLE_OK=0
check 'sentinel'   "$SENTINEL_URL/actuator/health"   || SENTINEL_OK=0
check 'es'         "$ES_URL/_cluster/health"         || ES_OK=0
echo ""

if [ "$SAMPLE_OK" -ne 1 ]; then
    echo -e "${RED}[FATAL]${NC} sample-app must be running. Start it with:"
    echo "        cd demo/sample-app && mvn spring-boot:run"
    exit 1
fi

# -------------------------------------------------------------------------- #
# Phase 1: hammer every bad payload
# -------------------------------------------------------------------------- #
echo "=== Phase 1: business-domain failures ==="

declare -a PAYLOAD_NAMES=(
    'loans:missing-source'
    'loans:amount-too-high'
    'accounts:missing-kyc'
    'accounts:bad-pan'
    'payments:invalid-card'
    'payments:fraud-velocity'
    'transfers:frozen-acct'
    'transfers:self'
)
declare -a PAYLOAD_URIS=(
    '/api/loans/disburse'
    '/api/loans/disburse'
    '/api/accounts'
    '/api/accounts'
    '/api/payments'
    '/api/payments'
    '/api/transfers'
    '/api/transfers'
)
declare -a PAYLOAD_BODIES=(
    '{"applicationId":"LA-301","amount":75000}'
    '{"applicationId":"LA-302","amount":50000000,"sourceAccount":"A-78901"}'
    '{"customerId":"C-12345","pan":"ABCDE1234F"}'
    '{"customerId":"C-12345","pan":"BAD","kycDocId":"doc-1"}'
    '{"cardNumber":"123","amount":1500,"currency":"INR"}'
    '{"cardNumber":"4111111111111111","amount":5000000,"currency":"INR"}'
    '{"fromAccount":"A-12345","toAccount":"FRZ-78901","amount":5000}'
    '{"fromAccount":"A-12345","toAccount":"A-12345","amount":5000}'
)

declare -A counts
total=0
for ((i=1; i<=CYCLES; i++)); do
    for ((j=0; j<${#PAYLOAD_NAMES[@]}; j++)); do
        total=$((total+1))
        status=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
            -X POST -H 'Content-Type: application/json' \
            -d "${PAYLOAD_BODIES[$j]}" "${SAMPLE_APP_URL}${PAYLOAD_URIS[$j]}")
        key="${PAYLOAD_NAMES[$j]}:${status}"
        counts[$key]=$(( ${counts[$key]:-0} + 1 ))
    done
    if (( i % 2 == 0 )); then echo "  cycle $i/$CYCLES done"; fi
done

echo ""
echo "  Sent $total requests across ${#PAYLOAD_NAMES[@]} endpoints"
echo "  Breakdown:"
for key in $(echo "${!counts[@]}" | tr ' ' '\n' | sort); do
    printf "    %-36s %s\n" "$key" "${counts[$key]}"
done
echo ""

# -------------------------------------------------------------------------- #
# Phase 2: optional producer burst
# -------------------------------------------------------------------------- #
if [ "$WITH_PRODUCER" = "1" ]; then
    echo "=== Phase 2: producer burst ==="
    PRODUCER_PY="$(dirname "$0")/sample-producer/producer.py"
    if [ ! -f "$PRODUCER_PY" ]; then
        echo -e "  ${YELLOW}[WARN]${NC} producer script not found at $PRODUCER_PY -- skipping"
    else
        echo "  Running producer service=$SERVICE rate=${PRODUCER_RATE}/s for ${PRODUCER_SECONDS}s..."
        SERVICE_NAME="$SERVICE" RATE_PER_SEC="$PRODUCER_RATE" DURATION_SEC="$PRODUCER_SECONDS" \
            ES_URL="$ES_URL" python3 "$PRODUCER_PY"
    fi
    echo ""
fi

# -------------------------------------------------------------------------- #
# Phase 3: wait for SENTINEL poll
# -------------------------------------------------------------------------- #
WAIT_SEC=35
echo "=== Phase 3: waiting ${WAIT_SEC}s for SENTINEL's ES poll + correlation tick ==="
for ((i=WAIT_SEC; i>0; i--)); do
    printf "\r  %d seconds remaining...  " "$i"
    sleep 1
done
printf "\r  done                            \n\n"

# -------------------------------------------------------------------------- #
# Phase 4: verify
# -------------------------------------------------------------------------- #
echo "=== Phase 4: verification ==="

if [ "$ES_OK" = "1" ]; then
    today=$(date -u +%Y.%m.%d)
    biz_count=$(curl -s "$ES_URL/logs-$today/_count?q=service.name:my-spring-boot-app+AND+log.level:ERROR" \
        | python3 -c 'import sys,json; print(json.load(sys.stdin).get("count",0))' 2>/dev/null || echo "?")
    echo "  ES: ERROR docs for my-spring-boot-app today  -> $biz_count"

    if [ "$WITH_PRODUCER" = "1" ]; then
        prod_count=$(curl -s "$ES_URL/logs-$today/_count?q=service.name:$SERVICE" \
            | python3 -c 'import sys,json; print(json.load(sys.stdin).get("count",0))' 2>/dev/null || echo "?")
        echo "  ES: total docs for $SERVICE                 -> $prod_count"
    fi

    sample_msg=$(curl -s "$ES_URL/logs-$today/_search?size=1&q=service.name:my-spring-boot-app+AND+log.level:ERROR" \
        | python3 -c 'import sys,json
try:
    h=json.load(sys.stdin).get("hits",{}).get("hits",[])
    if h: print(h[0]["_source"].get("message",""))
except: pass' 2>/dev/null)
    if [ -n "$sample_msg" ]; then
        echo ""
        echo "  Most recent ERROR log on my-spring-boot-app:"
        echo -e "    ${GRAY}${sample_msg}${NC}"
    fi
fi

if [ "$SENTINEL_OK" = "1" ]; then
    echo ""
    echo "  SENTINEL dashboard stats:"
    curl -s "$SENTINEL_URL/api/dashboard/stats" | python3 -c '
import sys,json
s=json.load(sys.stdin)
print(f"    open incidents           {s.get(\"openIncidents\",0)}")
print(f"    last 24h                 {s.get(\"incidentsLast24h\",0)}")
print(f"    last 7d                  {s.get(\"incidentsLast7d\",0)}")
print(f"    services tracked         {s.get(\"serviceCount\",0)}")
' 2>/dev/null

    echo ""
    echo "  Incidents detected TODAY (UTC):"
    curl -s "$SENTINEL_URL/api/dashboard/incidents/active" | python3 -c '
import sys,json,datetime
today=datetime.datetime.utcnow().date().isoformat()
active=json.load(sys.stdin) or []
new=[i for i in active if i.get("detectedAt","").startswith(today)]
if not new:
    print("    none -- check IntelliJ console for [correlation:fire] lines")
for i in sorted(new, key=lambda x: x.get("detectedAt",""), reverse=True):
    sev=str(i.get("severity",""))
    svc=str(i.get("serviceName",""))[:28].ljust(28)
    rule=str(i.get("correlationRule") or "-")[:28].ljust(28)
    ts=str(i.get("detectedAt",""))
    print(f"    {sev}  {svc}  {rule}  {ts}")
' 2>/dev/null
fi

echo ""
echo -e "${CYAN}=== done ===${NC}"
echo "Tip: open the dashboard and the AI Copilot tab - ask:"
echo '      "why are loans failing on my-spring-boot-app right now?"'
echo ""
