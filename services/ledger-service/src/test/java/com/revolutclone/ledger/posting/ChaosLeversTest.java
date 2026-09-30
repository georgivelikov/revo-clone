package com.revolutclone.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.revolutclone.ledger.chaos.ChaosLedgerState;
import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.domain.JournalEntryRepository;
import com.revolutclone.ledger.domain.JournalEntryType;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.LedgerAccountType;
import com.revolutclone.ledger.domain.PostingDirection;
import com.revolutclone.messaging.api.TransientMessagingException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * plans/phase-07-resilience-and-scale.md's Chaos control plane, exercised through
 * {@link PostingEngine} exactly as a real Kafka consumer thread would reach it - only
 * {@link ChaosLedgerState} is set directly here, standing in for
 * {@code ChaosLedgerAdminController}, which does the same thing over HTTP.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class ChaosLeversTest {

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
    private PostingEngine postingEngine;
    @Autowired
    private ChaosLedgerState chaos;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void resetChaos() {
        chaos.reset();
    }

    @Test
    void failRateOfOneHundredPercentThrowsTransientlyInsteadOfPosting() {
        UUID alice = openFundedWallet("EUR", 10_000);
        UUID bob = openFundedWallet("EUR", 0);
        chaos.setFailRatePercent(100);

        String idempotencyKey = "payment:" + UUID.randomUUID();
        assertThatThrownBy(() -> postingEngine.postEntry(new PostEntryCommand(
                idempotencyKey, JournalEntryType.TRANSFER, UUID.randomUUID(),
                List.of(
                        new PostingLine(alice, PostingDirection.DEBIT, 500, "EUR", null),
                        new PostingLine(bob, PostingDirection.CREDIT, 500, "EUR", null)))))
                .isInstanceOf(TransientMessagingException.class);

        // Nothing written - the injected failure happens before the executor is ever called.
        assertThat(journalEntryRepository.findByIdempotencyKey(idempotencyKey)).isEmpty();
    }

    @Test
    void rejectAllRejectsAWellFundedTransferAndEmitsThroughTheRealRejectionPath() {
        UUID alice = openFundedWallet("EUR", 10_000);
        UUID bob = openFundedWallet("EUR", 0);
        chaos.setRejectAll(true);

        PostEntryResult result = postingEngine.postEntry(new PostEntryCommand(
                "payment:" + UUID.randomUUID(), JournalEntryType.TRANSFER, UUID.randomUUID(),
                List.of(
                        new PostingLine(alice, PostingDirection.DEBIT, 500, "EUR", null),
                        new PostingLine(bob, PostingDirection.CREDIT, 500, "EUR", null))));

        assertThat(result).isInstanceOf(PostEntryResult.Rejected.class);
        assertThat(((PostEntryResult.Rejected) result).reason()).isEqualTo(EntryRejectionReason.INSUFFICIENT_FUNDS);

        AccountBalance aliceBalance = accountBalanceRepository.findById(alice).orElseThrow();
        assertThat(aliceBalance.getAvailableMinor()).isEqualTo(10_000);
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
}
