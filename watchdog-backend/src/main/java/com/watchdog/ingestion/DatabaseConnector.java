package com.watchdog.ingestion;

import com.watchdog.config.WatchdogProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * FR-5: read-only query connector against target application databases.
 *
 * Hard constraints:
 *  - SELECT only — the allow-list rejects everything else (no INSERT/UPDATE/DELETE/DDL)
 *  - row + timeout caps come from each target's config
 *  - one HikariDataSource per configured target; lazy-built on first use
 *
 * Connections are configured via {@code watchdog.db-targets.targets[]} (env-driven).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "watchdog.agent", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class DatabaseConnector {

    private static final Pattern SAFE_SELECT = Pattern.compile(
            "^\\s*(WITH\\s+.+\\s+)?SELECT\\s+", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Set<String> DENIED_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "TRUNCATE", "DROP", "ALTER",
            "CREATE", "GRANT", "REVOKE", "COMMIT", "ROLLBACK", "CALL", "EXECUTE", "DO");

    private final WatchdogProperties properties;
    private final Map<String, DataSource> pools = new ConcurrentHashMap<>();
    private final Map<String, WatchdogProperties.DbTargetsConfig.Target> targetsByName = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        for (var t : properties.getDbTargets().getTargets()) {
            if (t.getName() == null || t.getName().isBlank()
                    || t.getUrl() == null || t.getUrl().isBlank()) {
                log.warn("Skipping db target with missing name/url");
                continue;
            }
            targetsByName.put(t.getName(), t);
        }
        log.info("DatabaseConnector configured with {} read-only target(s): {}",
                targetsByName.size(), targetsByName.keySet());
    }

    @PreDestroy
    void shutdown() {
        for (DataSource ds : pools.values()) {
            if (ds instanceof HikariDataSource hd) hd.close();
        }
    }

    public Set<String> targetNames() {
        return Collections.unmodifiableSet(targetsByName.keySet());
    }

    public QueryResult query(String targetName, String sql, int requestedLimit) throws SQLException {
        // SQL safety check first (defense in depth — runs even if target lookup is bypassed in tests).
        rejectIfNotSelect(sql);
        var target = targetsByName.get(targetName);
        if (target == null) {
            throw new IllegalArgumentException("Unknown target '" + targetName
                    + "'. Configured: " + targetsByName.keySet());
        }

        int rowCap = Math.min(
                target.getQueryRowLimit() > 0 ? target.getQueryRowLimit() : 200,
                requestedLimit > 0 ? requestedLimit : 200);

        DataSource ds = pools.computeIfAbsent(targetName, k -> buildPool(target));
        try (Connection conn = ds.getConnection()) {
            conn.setReadOnly(true);
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(target.getQueryTimeoutSeconds());
                stmt.setMaxRows(rowCap);
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    return materialize(rs, rowCap);
                }
            } finally {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
        }
    }

    private void rejectIfNotSelect(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("empty sql");
        }
        String trimmed = sql.trim();
        if (trimmed.endsWith(";")) trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        if (trimmed.contains(";")) {
            throw new IllegalArgumentException("multi-statement SQL is not allowed");
        }
        if (!SAFE_SELECT.matcher(trimmed).find()) {
            throw new IllegalArgumentException("only SELECT (optionally with CTEs) is allowed");
        }
        String upper = " " + trimmed.toUpperCase(Locale.ROOT) + " ";
        for (String kw : DENIED_KEYWORDS) {
            if (upper.contains(" " + kw + " ")) {
                throw new IllegalArgumentException("denied keyword: " + kw);
            }
        }
    }

    private DataSource buildPool(WatchdogProperties.DbTargetsConfig.Target target) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(target.getUrl());
        cfg.setUsername(target.getUsername());
        cfg.setPassword(target.getPassword());
        cfg.setDriverClassName(target.getDriverClassName());
        cfg.setReadOnly(true);
        cfg.setMaximumPoolSize(3);
        cfg.setMinimumIdle(0);
        cfg.setPoolName("watchdog-ro-" + target.getName());
        cfg.setConnectionTimeout(5_000);
        return new HikariDataSource(cfg);
    }

    private QueryResult materialize(ResultSet rs, int rowCap) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        List<String> columns = new ArrayList<>(cols);
        for (int i = 1; i <= cols; i++) columns.add(md.getColumnLabel(i));

        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next() && rows.size() < rowCap) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= cols; i++) {
                row.put(columns.get(i - 1), rs.getObject(i));
            }
            rows.add(row);
        }
        boolean truncated = rs.next();
        return new QueryResult(columns, rows, truncated);
    }

    public record QueryResult(List<String> columns, List<Map<String, Object>> rows, boolean truncated) { }
}
