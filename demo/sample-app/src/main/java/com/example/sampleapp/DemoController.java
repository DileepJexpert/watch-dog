package com.example.sampleapp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Endpoints that cover the failure modes SENTINEL's 20 correlation rules
 * are tuned for. Hit them with curl or the run.ps1 driver script to drive
 * the dashboard.
 */
@RestController
@RequestMapping("/")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);
    private static final Random RND = new Random();

    private final Counter failCounter;
    private final Counter slowCounter;

    public DemoController(MeterRegistry registry) {
        this.failCounter = Counter.builder("app.demo.failures")
                .description("Synthetic failures from /fail and /db-fail")
                .register(registry);
        this.slowCounter = Counter.builder("app.demo.slow")
                .description("Slow requests served by /slow")
                .register(registry);
    }

    @GetMapping("/healthy")
    public Map<String, String> healthy() {
        log.info("Healthy request served");
        return Map.of("status", "ok");
    }

    /** Throws a generic 500 error — drives HIGH_ERROR_RATE rule. */
    @GetMapping("/fail")
    public Map<String, String> fail() {
        failCounter.increment();
        log.error("Synthetic failure in /fail endpoint",
                new RuntimeException("Synthetic failure: payment authorization rejected"));
        throw new RuntimeException("Synthetic failure: payment authorization rejected");
    }

    /**
     * Simulates a DB connectivity failure with the JdbcSQLException message
     * SENTINEL's DB_CONNECTIVITY rule keys off. Drives that rule directly.
     */
    @GetMapping("/db-fail")
    public Map<String, String> dbFail() {
        failCounter.increment();
        log.error("HikariPool-1 - Connection is not available, request timed out (active=20, idle=0)",
                new SQLException("Connection to payments-db:5432 refused"));
        throw new RuntimeException("JdbcSQLException: Connection is not available, request timed out after 30000ms");
    }

    /** Sleeps for ?ms=2000 (default) to push the latency percentile distribution. */
    @GetMapping("/slow")
    public Map<String, Object> slow(@RequestParam(defaultValue = "2000") long ms) throws InterruptedException {
        slowCounter.increment();
        long jitter = ThreadLocalRandom.current().nextLong(ms / 2);
        long sleep = ms + jitter;
        log.warn("Slow request — sleeping {}ms", sleep);
        Thread.sleep(sleep);
        return Map.of("slept_ms", sleep, "status", "ok");
    }

    /** Throws OutOfMemoryError-style log — drives the MEMORY_LEAK rule. */
    @GetMapping("/oom")
    public Map<String, String> oom() {
        failCounter.increment();
        log.error("OutOfMemoryError: Java heap space",
                new OutOfMemoryError("Java heap space"));
        throw new RuntimeException("OutOfMemoryError: Java heap space");
    }

    /** Random failure with 50% chance — useful for a steady error-rate stream. */
    @GetMapping("/flaky")
    public Map<String, String> flaky() {
        if (RND.nextBoolean()) {
            return fail();
        }
        return Map.of("status", "ok");
    }
}
