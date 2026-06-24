#!/usr/bin/env python3
"""
SENTINEL sample producer — emits all three signal types with zero deps.

What this does (no Filebeat / Spring Boot / Maven required):
  - POSTs ECS-shaped JSON logs DIRECTLY to Elasticsearch /_bulk
  - Exposes a /metrics endpoint in Prometheus exposition format on PROM_PORT
  - Sends OTLP/HTTP traces to Jaeger

Configurable via env vars (defaults match the docker-compose remapped ports):
  ES_URL          http://localhost:9201
  PROM_PORT       1882
  OTLP_URL        http://localhost:4318/v1/traces
  SERVICE_NAME    sample-producer
  RATE_PER_SEC    2                   (events per second)
  DURATION_SEC    0                   (0 = run forever; positive = stop after N seconds)
  WITH_TRACES     true                (set "false" to skip OTLP)
  WITH_LOGS       true
  WITH_METRICS    true

Stdlib only — runs on any Python 3.7+ install.

Quick start:
  python3 producer.py
  # Ctrl+C to stop.

Then verify:
  curl http://localhost:9201/logs-*/_count?q=service.name:sample-producer
  curl http://localhost:1882/metrics
  open http://localhost:16687  # filter Jaeger by Service=sample-producer
"""
from __future__ import annotations

import json
import os
import random
import signal
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# --------------------------------------------------------------------------- #
# Config
# --------------------------------------------------------------------------- #
ES_URL       = os.environ.get("ES_URL", "http://localhost:9201").rstrip("/")
PROM_PORT    = int(os.environ.get("PROM_PORT", "1882"))
OTLP_URL     = os.environ.get("OTLP_URL", "http://localhost:4318/v1/traces").rstrip("/")
SERVICE_NAME = os.environ.get("SERVICE_NAME", "sample-producer")
RATE_PER_SEC = float(os.environ.get("RATE_PER_SEC", "2"))
DURATION_SEC = int(os.environ.get("DURATION_SEC", "0"))
WITH_TRACES  = os.environ.get("WITH_TRACES", "true").lower() == "true"
WITH_LOGS    = os.environ.get("WITH_LOGS", "true").lower() == "true"
WITH_METRICS = os.environ.get("WITH_METRICS", "true").lower() == "true"

SCENARIOS = [
    # (weight, level, message, error_type)
    (5, "INFO",  "Request /api/healthy completed in 8ms",                              None),
    (4, "ERROR", "Synthetic failure: payment authorization rejected",                  "RuntimeException"),
    (3, "ERROR", "HikariPool-1 - Connection is not available, request timed out",      "java.sql.SQLException"),
    (2, "WARN",  "Slow downstream call to inventory-svc took 1842ms",                  None),
    (2, "ERROR", "OutOfMemoryError: Java heap space",                                  "java.lang.OutOfMemoryError"),
    (1, "ERROR", "java.net.SocketTimeoutException: Read timed out after 30000ms",      "java.net.SocketTimeoutException"),
]

# --------------------------------------------------------------------------- #
# Counters (shared between producer + metrics server)
# --------------------------------------------------------------------------- #
_lock = threading.Lock()
_metrics = {
    "events_total":           0,
    "logs_sent_total":        0,
    "logs_failed_total":      0,
    "traces_sent_total":      0,
    "traces_failed_total":    0,
    "requests_by_level":      {"INFO": 0, "WARN": 0, "ERROR": 0},
}


def _inc(key: str, n: int = 1, sub: str | None = None) -> None:
    with _lock:
        if sub is None:
            _metrics[key] += n
        else:
            _metrics[key][sub] = _metrics[key].get(sub, 0) + n


# --------------------------------------------------------------------------- #
# Logs -> Elasticsearch /_bulk
# --------------------------------------------------------------------------- #
def es_index_for_today() -> str:
    return "logs-" + datetime.now(timezone.utc).strftime("%Y.%m.%d")


def post_log(level: str, message: str, error_type: str | None) -> None:
    if not WITH_LOGS:
        return

    index = es_index_for_today()
    doc = {
        "@timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + f"{datetime.now(timezone.utc).microsecond // 1000:03d}Z",
        "service": {"name": SERVICE_NAME},
        "log": {"level": level},
        "message": message,
    }
    if error_type:
        doc["error"] = {"type": error_type, "message": message}

    # NDJSON bulk envelope: action line + doc line, both terminated by \n.
    body = (
        json.dumps({"index": {"_index": index}}) + "\n" +
        json.dumps(doc) + "\n"
    ).encode("utf-8")

    req = urllib.request.Request(
        f"{ES_URL}/_bulk",
        data=body,
        headers={"Content-Type": "application/x-ndjson"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
            if payload.get("errors"):
                _inc("logs_failed_total")
                print(f"[producer:es] indexed with errors: {payload!s:.200}", file=sys.stderr)
            else:
                _inc("logs_sent_total")
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as e:
        _inc("logs_failed_total")
        print(f"[producer:es] POST /_bulk failed: {e}", file=sys.stderr)


# --------------------------------------------------------------------------- #
# Traces -> OTLP/HTTP (Jaeger)
# --------------------------------------------------------------------------- #
def _hex(n_bytes: int) -> str:
    return uuid.uuid4().hex[:n_bytes * 2]


def post_trace(level: str, op_name: str) -> None:
    if not WITH_TRACES:
        return

    end_ns = time.time_ns()
    start_ns = end_ns - random.randint(5_000_000, 250_000_000)  # 5-250 ms spans
    span = {
        "resourceSpans": [{
            "resource": {
                "attributes": [
                    {"key": "service.name", "value": {"stringValue": SERVICE_NAME}},
                    {"key": "deployment.environment", "value": {"stringValue": "local-dev"}},
                ],
            },
            "scopeSpans": [{
                "scope": {"name": "sample-producer", "version": "1.0.0"},
                "spans": [{
                    "traceId":       _hex(16),
                    "spanId":        _hex(8),
                    "name":          op_name,
                    "kind":          2,          # SPAN_KIND_SERVER
                    "startTimeUnixNano": str(start_ns),
                    "endTimeUnixNano":   str(end_ns),
                    "status": {"code": 2 if level == "ERROR" else 1},   # 2=ERROR, 1=OK
                    "attributes": [
                        {"key": "http.method", "value": {"stringValue": "GET"}},
                        {"key": "http.route",  "value": {"stringValue": "/" + op_name}},
                        {"key": "log.level",   "value": {"stringValue": level}},
                    ],
                }],
            }],
        }],
    }

    req = urllib.request.Request(
        OTLP_URL,
        data=json.dumps(span).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as _:
            _inc("traces_sent_total")
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as e:
        _inc("traces_failed_total")
        print(f"[producer:otlp] POST {OTLP_URL} failed: {e}", file=sys.stderr)


# --------------------------------------------------------------------------- #
# /metrics endpoint for Prometheus
# --------------------------------------------------------------------------- #
class _MetricsHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):  # silence per-request access logs
        pass

    def do_GET(self):
        if self.path != "/metrics":
            self.send_response(404)
            self.end_headers()
            return

        with _lock:
            m = {k: (dict(v) if isinstance(v, dict) else v) for k, v in _metrics.items()}

        out: list[str] = []
        out.append("# TYPE sample_producer_events_total counter")
        out.append(f'sample_producer_events_total{{service="{SERVICE_NAME}"}} {m["events_total"]}')
        out.append("# TYPE sample_producer_logs_sent_total counter")
        out.append(f'sample_producer_logs_sent_total{{service="{SERVICE_NAME}"}} {m["logs_sent_total"]}')
        out.append("# TYPE sample_producer_logs_failed_total counter")
        out.append(f'sample_producer_logs_failed_total{{service="{SERVICE_NAME}"}} {m["logs_failed_total"]}')
        out.append("# TYPE sample_producer_traces_sent_total counter")
        out.append(f'sample_producer_traces_sent_total{{service="{SERVICE_NAME}"}} {m["traces_sent_total"]}')
        out.append("# TYPE sample_producer_traces_failed_total counter")
        out.append(f'sample_producer_traces_failed_total{{service="{SERVICE_NAME}"}} {m["traces_failed_total"]}')

        # Per-level request counter the SENTINEL correlation rules can read.
        out.append("# TYPE http_server_requests_total counter")
        for lvl, n in m["requests_by_level"].items():
            status = "200" if lvl == "INFO" else ("500" if lvl == "ERROR" else "499")
            out.append(
                f'http_server_requests_total'
                f'{{service="{SERVICE_NAME}",log_level="{lvl}",status="{status}"}} {n}'
            )

        body = ("\n".join(out) + "\n").encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; version=0.0.4")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def start_metrics_server() -> ThreadingHTTPServer:
    server = ThreadingHTTPServer(("0.0.0.0", PROM_PORT), _MetricsHandler)
    t = threading.Thread(target=server.serve_forever, daemon=True, name="metrics-server")
    t.start()
    print(f"[producer:metrics] /metrics listening on :{PROM_PORT}")
    return server


# --------------------------------------------------------------------------- #
# Main loop
# --------------------------------------------------------------------------- #
def _weighted_choice():
    bag: list[tuple[int, str, str, str | None]] = []
    for weight, level, msg, etype in SCENARIOS:
        bag.extend([(weight, level, msg, etype)] * weight)
    return random.choice(bag)


def main() -> int:
    print(f"[producer] starting — service={SERVICE_NAME} rate={RATE_PER_SEC}/s "
          f"logs={WITH_LOGS} traces={WITH_TRACES} metrics={WITH_METRICS}")
    print(f"[producer] ES={ES_URL}  OTLP={OTLP_URL}")

    server = start_metrics_server() if WITH_METRICS else None

    stop = threading.Event()

    def _sigint(*_):
        print("\n[producer] shutdown requested")
        stop.set()

    signal.signal(signal.SIGINT,  _sigint)
    signal.signal(signal.SIGTERM, _sigint)

    started = time.monotonic()
    interval = 1.0 / max(RATE_PER_SEC, 0.01)

    try:
        while not stop.is_set():
            _, level, message, error_type = _weighted_choice()
            _inc("events_total")
            _inc("requests_by_level", sub=level)

            post_log(level, message, error_type)
            post_trace(level, _op_name_for(level))

            if DURATION_SEC and (time.monotonic() - started) >= DURATION_SEC:
                break

            stop.wait(interval)
    finally:
        if server:
            server.shutdown()

        with _lock:
            print(f"[producer] done — events={_metrics['events_total']} "
                  f"logs_ok={_metrics['logs_sent_total']} "
                  f"logs_err={_metrics['logs_failed_total']} "
                  f"traces_ok={_metrics['traces_sent_total']} "
                  f"traces_err={_metrics['traces_failed_total']}")
    return 0


def _op_name_for(level: str) -> str:
    if level == "ERROR":
        return random.choice(["fail", "db-fail", "oom"])
    if level == "WARN":
        return "slow"
    return "healthy"


if __name__ == "__main__":
    sys.exit(main())
