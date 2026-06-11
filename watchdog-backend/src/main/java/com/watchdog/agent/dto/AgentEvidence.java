package com.watchdog.agent.dto;

/**
 * FR-9 evidence record: every claim the agent makes must be tied back to a tool call + source record.
 *
 * source: logs | traces | metrics | db | runtime | knowledge | correlation | anomaly | pod
 * ref:    opaque identifier (es_id, trace_id, prometheus query, db.table#row, knowledge_doc_id...)
 */
public record AgentEvidence(String source, String ref, String excerpt) {
}
