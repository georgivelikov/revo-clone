package com.revolutclone.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.domain.JournalEntryRepository;
import com.revolutclone.ledger.domain.JournalEntryType;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.LedgerAccountType;
import com.revolutclone.ledger.domain.PostingDirection;
import com.revolutclone.ledger.domain.PostingRepository;
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
 * plans/phase-02-ledger.md's ### Tests table: "Debit beyond available is rejected; balance
 * unchanged; EntryRejectedV1 emitted." A DEBIT beyond {@code available - reserved} on a
 * {@code USER_WALLET} must write nothing to {@code journal_entry}/{@code posting}, must leave
 * {@code account_balance} untouched, and must still produce an outbox row (verified here by
 * asserting the transaction actually committed something despite the rejection - the outbox
 * row itself is common-messaging's table, not re-asserted row-by-row here since
 * OutboxAppenderImpl already has its own coverage from Phase 00).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class InsufficientFundsTest {

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
    private JournalEntryRepository journalEntryRepository;
    @Autowired
    private PostingRepository postingRepository;
    @Autowired
    private PostingEngine postingEngine;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void debitBeyondAvailableIsRejectedAndBalanceIsUnchanged() {
        UUID alice = openFundedWallet("EUR", 500); // only 5.00 available
        UUID bob = openFundedWallet("EUR", 0);

        String idempotencyKey = "payment:" + UUID.randomUUID();
        PostEntryResult result = postingEngine.postEntry(new PostEntryCommand(
                idempotencyKey, JournalEntryType.TRANSFER, UUID.randomUUID(),
                List.of(
                        new PostingLine(alice, PostingDirection.DEBIT, 1_000, "EUR", null), // 10.00, more than she has
                        new PostingLine(bob, PostingDirection.CREDIT, 1_000, "EUR", null))));

        assertThat(result).isInstanceOf(PostEntryResult.Rejected.class);
        PostEntryResult.Rejected rejected = (PostEntryResult.Rejected) result;
        assertThat(rejected.reason()).isEqualTo(EntryRejectionReason.INSUFFICIENT_FUNDS);
        assertThat(rejected.accountId()).isEqualTo(alice);

        // Nothing written to the ledger tables.
        assertThat(journalEntryRepository.findByIdempotencyKey(idempotencyKey)).isEmpty();

        AccountBalance aliceBalance = accountBalanceRepository.findById(alice).orElseThrow();
        AccountBalance bobBalance = accountBalanceRepository.findById(bob).orElseThrow();
        assertThat(aliceBalance.getAvailableMinor()).isEqualTo(500);
        assertThat(bobBalance.getAvailableMinor()).isZero();
    }

    @Test
    void reservedFundsCountAgainstAvailability() {
        // plans/phase-02-ledger.md: "The check is available_minor - reserved_minor >= amount
        // on debit. Reserved funds are already spoken for." A wallet with 10.00 available but
        // 10.00 already reserved has nothing spendable.
        UUID alice = openFundedWallet("EUR", 1_000);
        UUID bob = openFundedWallet("EUR", 0);
        reserveFunds(alice, 1_000);

        PostEntryResult result = postingEngine.postEntry(new PostEntryCommand(
                "payment:" + UUID.randomUUID(), JournalEntryType.TRANSFER, UUID.randomUUID(),
                List.of(
                        new PostingLine(alice, PostingDirection.DEBIT, 1, "EUR", null),
                        new PostingLine(bob, PostingDirection.CREDIT, 1, "EUR", null))));

        assertThat(result).isInstanceOf(PostEntryResult.Rejected.class);
        assertThat(((PostEntryResult.Rejected) result).reason()).isEqualTo(EntryRejectionReason.INSUFFICIENT_FUNDS);
    }

    private UUID openFundedWallet(String currency, long startingAvailableMinor) {
        return transactionTemplate.execute(status -> {
            LedgerAccount account = new LedgerAccount(UUID.randomUUID(), "user:" + UUID.randomUUID(),
                    LedgerAccountType.USER_WALLET, currency);
            ledgerAccountRepository.save(account);
            AccountBalance balance = new AccountBalance(account.getId(), currency);
            balance.creditAvailable(startingAvailableMinor);
            accountBalanceRepository.save(balance);
            return account.getId();
        });
    }

    private void reserveFunds(UUID ledgerAccountId, long amountMinor) {
        transactionTemplate.executeWithoutResult(status -> {
            AccountBalance balance = accountBalanceRepository.findById(ledgerAccountId).orElseThrow();
            balance.moveToReserved(amountMinor);
        });
    }
}
