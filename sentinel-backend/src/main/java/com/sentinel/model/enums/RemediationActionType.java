package com.sentinel.model.enums;

public enum RemediationActionType {
    POD_SCALE_HORIZONTAL,
    POD_RESTART,
    DEPLOYMENT_ROLLBACK,
    CIRCUIT_BREAKER_OPEN,
    CIRCUIT_BREAKER_CLOSE,
    CACHE_FLUSH,
    ESCALATE_TO_HUMAN
}
