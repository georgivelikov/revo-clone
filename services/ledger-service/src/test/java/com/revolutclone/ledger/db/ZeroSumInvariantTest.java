package com.revolutclone.ledger.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * plans/phase-02-ledger.md {@code ## Tests}: "After an arbitrary sequence of operations,
 * {@code sum(postings)} per currency is 0". At this stage of the phase (task 02.2, no
 * {@code PostingEngine} yet), "operations" means raw-SQL-inserted balanced entries, mirroring
 * {@link BalancedEntryTest}. Once {@code PostingEngine} exists, later tasks add a much richer
 * version of this same invariant, exercised through real application code and concurrent
 * traffic - this one proves the invariant holds at the database level for the simplest
 * possible sequence, using the exact query shape from the phase spec's reconciliation
 * section (sum of CREDIT minus DEBIT, grouped by currency).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class ZeroSumInvariantTest {

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
    void sumOfPostingsPerCurrencyIsZeroAfterSeveralBalancedEntries() throws SQLException {
        UUID eurA = createLedgerAccount("EUR");
        UUID eurB = createLedgerAccount("EUR");
        UUID usdA = createLedgerAccount("USD");
        UUID usdB = createLedgerAccount("USD");

        postBalancedEntry(eurA, eurB, 1_000, "EUR");
        postBalancedEntry(eurB, eurA, 400, "EUR");
        postBalancedEntry(eurA, eurB, 250, "EUR");
        postBalancedEntry(usdA, usdB, 2_500, "USD");
        postBalancedEntry(usdB, usdA, 2_500, "USD");

        Map<String, Long> netByCurrency = queryNetByCurrency();

        assertThat(netByCurrency.getOrDefault("EUR", 0L)).isZero();
        assertThat(netByCurrency.getOrDefault("USD", 0L)).isZero();
    }

    private void postBalancedEntry(UUID debitAccount, UUID creditAccount, long amountMinor, String currency)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            UUID entryId = insertJournalEntry(connection, "entry-" + UUID.randomUUID());
            insertPosting(connection, entryId, debitAccount, "DEBIT", amountMinor, currency, 1);
            insertPosting(connection, entryId, creditAccount, "CREDIT", amountMinor, currency, 2);
            connection.commit();
        }
    }

    private Map<String, Long> queryNetByCurrency() throws SQLException {
        // Same shape as plans/phase-02-ledger.md's ### Reconciliation query: CREDIT positive,
        // DEBIT negative, grouped by currency.
        Map<String, Long> result = new HashMap<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select currency, "
                                + "sum(case when direction = 'CREDIT' then amount_minor else -amount_minor end) as net "
                                + "from posting group by currency");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.put(rs.getString("currency"), rs.getLong("net"));
            }
        }
        return result;
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
