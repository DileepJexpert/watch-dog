#!/usr/bin/env bash
# Ask the AI Copilot a question through the synchronous endpoint.
# Usage: ./demo/ask-agent.sh "why is payments slow right now?"

set -euo pipefail
API="${SENTINEL_API:-http://localhost:8080}"
QUESTION="${1:-why is payments slow right now?}"
SESSION="cli-$(date +%s)"

echo "[ask] session=${SESSION}"
echo "[ask] question: ${QUESTION}"
echo

curl -sS -X POST "${API}/api/agent/ask" \
  -H 'Content-Type: application/json' \
  -d "$(python3 -c "import json,sys; print(json.dumps({'question': sys.argv[1], 'sessionId': sys.argv[2]}))" "$QUESTION" "$SESSION")" \
  | python3 -m json.tool
