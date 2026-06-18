package com.sentinel;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Smoke test: verifies the Spring application context loads successfully.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sentinel_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        // Flyway migrations are Postgres-specific (TimescaleDB hypertables in V1);
        // let Hibernate create + drop the schema from JPA mappings on H2 instead.
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.bootstrap-servers=localhost:9092",
        "spring.data.redis.host=localhost",
        "sentinel.remediation.dry-run=true"
})
class SentinelApplicationTest {

    @Test
    void contextLoads() {
        // If the context loads without error, the test passes
    }
}
