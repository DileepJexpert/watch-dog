"""
Mock LLM server speaking the Anthropic /v1/messages envelope.

Lets you exercise the full SENTINEL agent loop end-to-end without an external
API key. Decisions are heuristic based on the most recent user message:

  - First turn: call search_logs + summarize_logs in parallel.
  - If the question mentions latency / slow / trace, also call get_traces.
  - If the prior message contains tool_result content, finalize the answer.

Run:  python3 mock-llm.py            (listens on 127.0.0.1:4000)
"""
import json
import sys
from http.server import HTTPServer, BaseHTTPRequestHandler

PORT = 4000


def latest_user_text(messages):
    for m in reversed(messages):
        if m.get("role") == "user":
            c = m.get("content", "")
            if isinstance(c, str):
                return c.lower()
            for block in c:
                if isinstance(block, dict) and block.get("type") == "text":
                    return block.get("text", "").lower()
    return ""


def has_tool_results(messages):
    for m in messages:
        c = m.get("content", "")
        if isinstance(c, list):
            for block in c:
                if isinstance(block, dict) and block.get("type") == "tool_result":
                    return True
    return False


def assistant_text_so_far(messages):
    out = []
    for m in messages:
        if m.get("role") != "assistant":
            continue
        c = m.get("content", "")
        if isinstance(c, list):
            for block in c:
                if isinstance(block, dict) and block.get("type") == "text":
                    out.append(block.get("text", ""))
    return " ".join(out).lower()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("[mock-llm] " + (fmt % args) + "\n")

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length))
        messages = body.get("messages", [])
        question = latest_user_text(messages)
        tool_results_in_history = has_tool_results(messages)

        # If we've already seen tool results in the conversation, finalize.
        if tool_results_in_history:
            answer = {
                "summary": "Recent ERROR logs from the payments service show a burst of "
                           "JdbcSQLException entries clustered in the last 2 minutes, "
                           "consistent with a connection-pool exhaustion pattern.",
                "rootCause": {
                    "hypothesis": "Database connection pool exhaustion on payments-svc — "
                                  "incoming requests cannot acquire a connection within "
                                  "the configured timeout.",
                    "confidence": "medium"
                },
                "evidence": [
                    {"source": "logs", "ref": "es_id=mock-1",
                     "excerpt": "JdbcSQLException: Connection is not available, request timed out after 30000ms"},
                    {"source": "logs", "ref": "es_id=mock-2",
                     "excerpt": "HikariPool-1 - Connection is not available, request timed out"},
                    {"source": "knowledge", "ref": "doc_id=runbook",
                     "excerpt": "When pool exhaustion is suspected, check active txn count vs max-pool-size"}
                ],
                "recommendedActions": [
                    {"action": "Inspect active transactions on payments DB and identify long-running queries",
                     "rationale": "Long-running transactions are the most common cause of pool exhaustion.",
                     "requiresApproval": True},
                    {"action": "Temporarily raise spring.datasource.hikari.maximum-pool-size from 20 → 40",
                     "rationale": "Provides headroom while the root cause is diagnosed.",
                     "requiresApproval": True}
                ]
            }
            resp = {
                "content": [
                    {"type": "text", "text": "Synthesizing final answer."},
                    {"type": "tool_use", "id": "tu_final", "name": "finalize_answer", "input": answer}
                ],
                "stop_reason": "tool_use",
                "usage": {"input_tokens": 320, "output_tokens": 180}
            }
        else:
            # First turn — pick tools to call based on the question.
            blocks = [{"type": "text", "text": "Gathering evidence in parallel."}]
            service = "payments"
            for known in ("payments", "auth", "orders", "checkout"):
                if known in question:
                    service = known
                    break
            blocks.append({"type": "tool_use", "id": "tu_logs", "name": "summarize_logs",
                           "input": {"service": service, "maxBuckets": 5}})
            blocks.append({"type": "tool_use", "id": "tu_search", "name": "search_logs",
                           "input": {"service": service, "limit": 20}})
            if any(k in question for k in ("slow", "latency", "trace", "p95", "p99")):
                blocks.append({"type": "tool_use", "id": "tu_trace", "name": "get_traces",
                               "input": {"service": service, "limit": 10}})
            if any(k in question for k in ("runbook", "knowledge", "incident", "past")):
                blocks.append({"type": "tool_use", "id": "tu_kb", "name": "search_knowledge",
                               "input": {"query": question[:200], "topK": 3}})
            resp = {
                "content": blocks,
                "stop_reason": "tool_use",
                "usage": {"input_tokens": 180, "output_tokens": 60}
            }

        body_out = json.dumps(resp).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body_out)))
        self.end_headers()
        self.wfile.write(body_out)


if __name__ == "__main__":
    print(f"[mock-llm] listening on http://127.0.0.1:{PORT}")
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
