package com.revolutclone.ledger.db;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
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
 * plans/phase-02-ledger.md {@code ## Tests}: "Unbalanced entry is rejected by code and by
 * the trigger (test the trigger directly with raw SQL)". No {@code PostingEngine} exists yet
 * (task 02.4), so this exercises only the database-level half of the double enforcement,
 * inserting rows with plain JDBC rather than through application code - which is the whole
 * point of a DB-level check: it must hold even when application code is bypassed entirely,
 * by a migration, a support script, or a bug.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BalancedEntryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void oidcIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://127.0.0.1:8180/realms/revolut");
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void balancedEntryCommitsSuccessfully() throws SQLException {
        UUID accountA = createLedgerAccount("EUR");
        UUID accountB = createLedgerAccount("EUR");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            UUID entryId = insertJournalEntry(connection, "entry-" + UUID.randomUUID());
            insertPosting(connection, entryId, accountA, "DEBIT", 1000, "EUR", 1);
            insertPosting(connection, entryId, accountB, "CREDIT", 1000, "EUR", 2);
            connection.commit();
        }
    }

    @Test
    void unbalancedEntryIsRejectedAtCommit() throws SQLException {
        UUID accountA = createLedgerAccount("EUR");
        UUID accountB = createLedgerAccount("EUR");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            UUID entryId = insertJournalEntry(connection, "entry-" + UUID.randomUUID());
            insertPosting(connection, entryId, accountA, "DEBIT", 1000, "EUR", 1);
            // Deliberately unbalanced: credit is 900, not the matching 1000.
            insertPosting(connection, entryId, accountB, "CREDIT", 900, "EUR", 2);

            assertThatThrownBy(connection::commit).hasMessageContaining("not balanced");
            connection.rollback();
        }
    }

    @Test
    void unbalancedEntryAcrossThreeCurrenciesIsRejected() throws SQLException {
        // A single entry can legitimately touch several currencies (task 02.4's
        // MultiCurrencyEntryTest); the trigger must balance EACH currency independently, not
        // the entry's overall net, so an entry balanced in EUR and USD but not GBP must still
        // be rejected.
        UUID eurA = createLedgerAccount("EUR");
        UUID eurB = createLedgerAccount("EUR");
        UUID usdA = createLedgerAccount("USD");
        UUID usdB = createLedgerAccount("USD");
        UUID gbpA = createLedgerAccount("GBP");
        UUID gbpB = createLedgerAccount("GBP");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            UUID entryId = insertJournalEntry(connection, "entry-" + UUID.randomUUID());
            insertPosting(connection, entryId, eurA, "DEBIT", 500, "EUR", 1);
            insertPosting(connection, entryId, eurB, "CREDIT", 500, "EUR", 2);
            insertPosting(connection, entryId, usdA, "DEBIT", 300, "USD", 3);
            insertPosting(connection, entryId, usdB, "CREDIT", 300, "USD", 4);
            insertPosting(connection, entryId, gbpA, "DEBIT", 200, "GBP", 5);
            insertPosting(connection, entryId, gbpB, "CREDIT", 150, "GBP", 6);

            assertThatThrownBy(connection::commit).hasMessageContaining("not balanced");
            connection.rollback();
        }
    }

    private UUID createLedgerAccount(String currency) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "insert into ledger_account (id, owner_ref, type, currency) values (?, ?, 'USER_WALLET', ?)")) {
            ps.setObject(1, id);
            ps.setString(2, "user:" + id);
            ps.setString(3, currency);
            ps.executeUpdate();
        }
        return id;
    }

    private UUID insertJournalEntry(Connection connection, String idempotencyKey) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(
                "insert into journal_entry (id, idempotency_key, type) values (?, ?, 'ADJUSTMENT')")) {
            ps.setObject(1, id);
            ps.setString(2, idempotencyKey);
            ps.executeUpdate();
        }
        return id;
    }

    private void insertPosting(Connection connection, UUID entryId, UUID accountId, String direction,
            long amountMinor, String currency, int seq) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "insert into posting (id, entry_id, ledger_account_id, direction, amount_minor, currency, seq) "
                        + "values (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, entryId);
            ps.setObject(3, accountId);
            ps.setString(4, direction);
            ps.setLong(5, amountMinor);
            ps.setString(6, currency);
            ps.setInt(7, seq);
            ps.executeUpdate();
        }
    }
}
