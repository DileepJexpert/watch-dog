package com.sentinel.ingestion;

import com.sentinel.config.SentinelProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-5 acceptance: writes and DDL are rejected even before a connection is opened.
 */
class DatabaseConnectorTest {

    private final DatabaseConnector connector = new DatabaseConnector(new SentinelProperties());

    @Test
    void rejectsInsert() {
        assertThatThrownBy(() -> connector.query("nope", "INSERT INTO t VALUES (1)", 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUpdate() {
        assertThatThrownBy(() -> connector.query("nope", "UPDATE t SET x=1", 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDdl() {
        assertThatThrownBy(() -> connector.query("nope", "DROP TABLE t", 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> connector.query("nope", "ALTER TABLE t ADD COLUMN x int", 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMultiStatement() {
        assertThatThrownBy(() -> connector.query("nope", "SELECT 1; DELETE FROM t", 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCall() {
        assertThatThrownBy(() -> connector.query("nope", "CALL dropEverything()", 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownTargetEvenWithValidSelect() {
        assertThatThrownBy(() -> connector.query("nope", "SELECT 1", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown target");
    }
}
