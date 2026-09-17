package com.revolutclone.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves V3__ledger.sql (plus common-messaging's shared V1/V2 migrations) actually applies
 * cleanly against a real Postgres - not just that the SQL parses, but that every table,
 * index and foreign key (including the deliberate reservation-before-posting forward
 * reference) resolves. No business logic exists yet (task 02.1 is scaffold + schema only),
 * so this is the whole of this task's test coverage, and it is exactly what the task needs
 * proven. Boot auto-discovers {@link LedgerServiceApplication} as the test configuration
 * since this test lives in the same package.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LedgerSchemaMigrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void oidcIssuer(DynamicPropertyRegistry registry) {
        // No Keycloak container needed for a schema-only test; the resource-server bean just
        // needs a syntactically valid issuer-uri to construct at context startup.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://127.0.0.1:8180/realms/revolut");
    }

    private static final Set<String> EXPECTED_TABLES = Set.of(
            "ledger_account", "journal_entry", "reservation", "posting", "account_balance",
            "outbox_event", "processed_event", "flyway_schema_history");

    @Autowired
    private DataSource dataSource;

    @Test
    void migrationAppliesCleanlyAndCreatesEveryTable() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            Set<String> actualTables = new HashSet<>();
            try (ResultSet rs = metadata.getTables(null, "public", "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    actualTables.add(rs.getString("TABLE_NAME"));
                }
            }
            assertThat(actualTables).containsAll(EXPECTED_TABLES);
        }
    }
}
