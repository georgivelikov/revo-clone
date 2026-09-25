package com.revolutclone.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.domain.JournalEntryType;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.LedgerAccountType;
import com.revolutclone.ledger.domain.PostingDirection;
import com.revolutclone.ledger.domain.SystemAccountRefs;
import com.revolutclone.ledger.posting.PostEntryCommand;
import com.revolutclone.ledger.posting.PostEntryResult;
import com.revolutclone.ledger.posting.PostingEngine;
import com.revolutclone.ledger.posting.PostingLine;
import com.revolutclone.ledger.reservation.ReservationService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * plans/phase-02-ledger.md's {@code ## Tests} table: "Corrupt a balance via raw SQL -> job
 * reports exactly that account." Three accounts: one healthy with ordinary postings, one
 * deliberately corrupted via {@link AccountBalance#corruptAvailableForTesting}, and one healthy
 * but carrying an active hold - proving the reconciliation query compares against
 * {@code available + reserved}, not {@code available} alone, per the phase's explicit warning
 * that comparing against {@code available} alone would flag every held account as "drifted".
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000"
})
class ReconciliationDetectsDriftTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void oidcIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://127.0.0.1:8180/realms/revolut");
    }

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private AccountBalanceRepository accountBalanceRepository;
    @Autowired
    private PostingEngine postingEngine;
    @Autowired
    private ReservationService reservationService;
    @Autowired
    private ReconciliationService reconciliationService;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void reportsExactlyTheCorruptedAccountAndNotAHealthyHeldAccount() {
        // Every account's starting balance is a REAL posting (funded from the seeded
        // EXTERNAL_CLEARING system account, exactly like the phase's own Verification curl
        // example) rather than a direct AccountBalance mutation - otherwise the initial credit
        // itself would never appear in `posting`, and every account in this test would drift
        // for that reason alone, defeating the point of the test.
        UUID healthy = openZeroBalanceWallet();
        UUID corrupted = openZeroBalanceWallet();
        UUID heldButHealthy = openZeroBalanceWallet();
        fund(healthy, 10_000);
        fund(corrupted, 10_000);
        fund(heldButHealthy, 10_000);

        // A live hold on the third account: available -= amount, reserved += amount, no posting.
        // A correct reconciliation query must NOT flag this account.
        reservationService.hold(heldButHealthy, 2_000, "EUR", UUID.randomUUID(),
                "cardauth:" + UUID.randomUUID(), Instant.now().plusSeconds(600));

        // Deliberately desync exactly one account's cached total from sum(postings).
        transactionTemplate.executeWithoutResult(status -> {
            AccountBalance managed = accountBalanceRepository.findById(corrupted).orElseThrow();
            managed.corruptAvailableForTesting(500);
        });

        List<AccountBalanceRepository.DriftRow> drifted = reconciliationService.reconcile();

        assertThat(drifted).as("exactly one account should be reported as drifted").hasSize(1);
        assertThat(drifted.get(0).getAccountId()).isEqualTo(corrupted);
        assertThat(drifted.get(0).getComputedMinor())
                .as("computed-from-postings total is unaffected by the corruption")
                .isEqualTo(10_000);
        assertThat(drifted.get(0).getCachedMinor())
                .as("cached total reflects the corruption (available was bumped by 500)")
                .isEqualTo(10_500);

        List<UUID> driftedIds = drifted.stream().map(AccountBalanceRepository.DriftRow::getAccountId).toList();
        assertThat(driftedIds).as("healthy account must not be reported").doesNotContain(healthy);
        assertThat(driftedIds).as("held-but-otherwise-healthy account must not be reported (available+reserved, "
                + "not available alone)").doesNotContain(heldButHealthy);
    }

    private void fund(UUID account, long amountMinor) {
        UUID externalClearing = ledgerAccountRepository
                .findByOwnerRefAndTypeAndCurrency(SystemAccountRefs.EXTERNAL_CLEARING,
                        LedgerAccountType.EXTERNAL_CLEARING, "EUR")
                .orElseThrow(() -> new IllegalStateException("EXTERNAL_CLEARING/EUR system account missing - "
                        + "expected from task 02.7's V5__system_accounts.sql"))
                .getId();
        PostEntryResult result = postingEngine.postEntry(new PostEntryCommand(
                "payment:" + UUID.randomUUID(), JournalEntryType.TRANSFER, UUID.randomUUID(),
                List.of(
                        new PostingLine(externalClearing, PostingDirection.DEBIT, amountMinor, "EUR", null),
                        new PostingLine(account, PostingDirection.CREDIT, amountMinor, "EUR", null))));
        assertThat(result).isInstanceOf(PostEntryResult.Posted.class);
    }

    private UUID openZeroBalanceWallet() {
        return transactionTemplate.execute(status -> {
            LedgerAccount account = new LedgerAccount(UUID.randomUUID(), "user:" + UUID.randomUUID(),
                    LedgerAccountType.USER_WALLET, "EUR");
            ledgerAccountRepository.save(account);
            accountBalanceRepository.save(new AccountBalance(account.getId(), "EUR"));
            return account.getId();
        });
    }
}
