package com.sentinel.model.enums;

/**
 * Alert severity tiers aligned with SENTINEL's tiered alerting model.
 * P1: Revenue-impacting customer-facing outage
 * P2: Degraded performance / partial outage
 * P3: Anomaly detected, no customer impact yet
 * P4: FYI trend shift, threshold approaching
 */
public enum Severity {
    P1_CRITICAL(1, "P1 Critical"),
    P2_HIGH(2, "P2 High"),
    P3_MEDIUM(3, "P3 Medium"),
    P4_INFO(4, "P4 Info");

    private final int level;
    private final String label;

    Severity(int level, String label) {
        this.level = level;
        this.label = label;
    }

    public int getLevel() { return level; }
    public String getLabel() { return label; }

    public boolean isHigherThan(Severity other) {
        return this.level < other.level;
    }
}
