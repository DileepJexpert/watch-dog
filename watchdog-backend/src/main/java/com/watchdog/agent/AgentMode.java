package com.watchdog.agent;

public enum AgentMode {
    ADVISORY,
    ASSISTED;

    public static AgentMode parse(String value) {
        if (value == null) return ADVISORY;
        return "assisted".equalsIgnoreCase(value.trim()) ? ASSISTED : ADVISORY;
    }
}
