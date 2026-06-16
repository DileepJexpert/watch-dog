package com.sentinel.agent;

public enum Confidence {
    LOW, MEDIUM, HIGH;

    public static Confidence parse(String value) {
        if (value == null) return LOW;
        return switch (value.trim().toLowerCase()) {
            case "high" -> HIGH;
            case "medium" -> MEDIUM;
            default -> LOW;
        };
    }
}
