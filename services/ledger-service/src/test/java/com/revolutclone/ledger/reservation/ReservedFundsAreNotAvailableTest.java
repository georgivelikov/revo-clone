package com.revolutclone.ledger.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.domain.JournalEntryType;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.LedgerAccountType;
import com.revolutclone.ledger.domain.PostingDirection;
import com.revolutclone.ledger.posting.EntryRejectionReason;
import com.revolutclone.ledger.posting.PostEntryCommand;
import com.revolutclone.ledger.posting.PostEntryResult;
import com.revolutclone.ledger.posting.PostingEngine;
import com.revolutclone.ledger.posting.PostingLine;
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
 * plans/phase-02-ledger.md's {@code ## Tests} table: "Hold then attempt to spend the held
 * amount -> rejected." A hold moves money out of {@code available} directly (per the
 * balance-column-semantics table: {@code available -= amount, reserved += amount}), so
 * {@code available} is already hold-aware by the time a later plain debit checks it - the
 * spendable check is {@code available >= amount}, not {@code available - reserved >= amount},
 * which would double-penalize an account that already has a hold on it. Both tests below exist
 * to prove that specifically: {@link #aDebitCoveredByAvailableAloneIsAllowedDespiteAnActiveHold}
 * is the one that actually distinguishes the two formulas (a bug here was caught live while
 * building this test, in code committed under task 02.4 before any hold ever put a nonzero
 * value in {@code reserved} to expose it - see {@code PostingTransactionExecutor.validateLine}).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class ReservedFundsAreNotAvailableTest {

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
    private ReservationService reservationService;
    @Autowired
    private PostingEngine postingEngine;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void holdingFundsMakesThemUnavailableForAPlainDebit() {
        UUID account = openFundedWallet(10_000); // 100.00 EUR
        UUID counterparty = openFundedWallet(0);

        reservationService.hold(account, 6_000, "EUR", UUID.randomUUID(), "cardauth:" + UUID.randomUUID(),
                Instant.now().plusSeconds(600));
        // After the hold: available=4_000, reserved=6_000 (100.00 - 60.00 held = 40.00 truly
        // spendable). Attempting to also debit 5_000 must be rejected: 4_000 < 5_000.

        PostEntryResult result = postingEngine.postEntry(new PostEntryCommand("payment:" + UUID.randomUUID(),
                JournalEntryType.TRANSFER, UUID.randomUUID(),
                List.of(
                        new PostingLine(account, PostingDirection.DEBIT, 5_000, "EUR", null),
                        new PostingLine(counterparty, PostingDirection.CREDIT, 5_000, "EUR", null))));

        assertThat(result).isInstanceOf(PostEntryResult.Rejected.class);
        assertThat(((PostEntryResult.Rejected) result).reason()).isEqualTo(EntryRejectionReason.INSUFFICIENT_FUNDS);

        AccountBalance balance = accountBalanceRepository.findById(account).orElseThrow();
        assertThat(balance.getAvailableMinor()).as("rejected entry must move nothing").isEqualTo(4_000);
        assertThat(balance.getReservedMinor()).isEqualTo(6_000);
    }

    @Test
    void aDebitCoveredByAvailableAloneIsAllowedDespiteAnActiveHold() {
        // Distinguishes `available >= amount` (correct) from `available - reserved >= amount`
        // (the bug): 100.00 wallet, hold 30.00 -> available=70.00, reserved=30.00. A genuinely
        // separate 50.00 debit is well within the true remaining 70.00 and MUST succeed - the
        // buggy formula would compute 70.00 - 30.00 = 40.00 and wrongly reject it.
        UUID account = openFundedWallet(10_000); // 100.00 EUR
        UUID counterparty = openFundedWallet(0);

        reservationService.hold(account, 3_000, "EUR", UUID.randomUUID(), "cardauth:" + UUID.randomUUID(),
                Instant.now().plusSeconds(600));

        PostEntryResult result = postingEngine.postEntry(new PostEntryCommand("payment:" + UUID.randomUUID(),
                JournalEntryType.TRANSFER, UUID.randomUUID(),
                List.of(
                        new PostingLine(account, PostingDirection.DEBIT, 5_000, "EUR", null),
                        new PostingLine(counterparty, PostingDirection.CREDIT, 5_000, "EUR", null))));

        assertThat(result).isInstanceOf(PostEntryResult.Posted.class);

        AccountBalance balance = accountBalanceRepository.findById(account).orElseThrow();
        assertThat(balance.getAvailableMinor()).isEqualTo(2_000); // 100.00 - 30.00 held - 50.00 spent
        assertThat(balance.getReservedMinor()).isEqualTo(3_000); // the hold is untouched
    }

    private UUID openFundedWallet(long startingAvailableMinor) {
        return transactionTemplate.execute(status -> {
            LedgerAccount account = new LedgerAccount(UUID.randomUUID(), "user:" + UUID.randomUUID(),
                    LedgerAccountType.USER_WALLET, "EUR");
            ledgerAccountRepository.save(account);
            AccountBalance balance = new AccountBalance(account.getId(), "EUR");
            balance.creditAvailable(startingAvailableMinor);
            accountBalanceRepository.save(balance);
            return account.getId();
        });
    }
}
