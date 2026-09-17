package com.revolutclone.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * plans/phase-02-ledger.md's ### Tests table: "Same idempotency_key twice -> one entry, one
 * set of postings, same response." Replaying a call must not double-move money, and must not
 * re-validate against the (possibly now-different) balance - it returns exactly what the
 * first call returned.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IdempotentEntryTest {

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
    private PostingRepository postingRepository;
    @Autowired
    private PostingEngine postingEngine;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void sameIdempotencyKeyTwiceProducesOneEntryOneSetOfPostingsSameResponse() {
        UUID alice = openFundedWallet("EUR", 10_000);
        UUID bob = openFundedWallet("EUR", 0);
        String idempotencyKey = "payment:" + UUID.randomUUID();

        PostEntryCommand command = new PostEntryCommand(idempotencyKey, JournalEntryType.TRANSFER,
                UUID.randomUUID(),
                List.of(
                        new PostingLine(alice, PostingDirection.DEBIT, 1_000, "EUR", null),
                        new PostingLine(bob, PostingDirection.CREDIT, 1_000, "EUR", null)));

        PostEntryResult first = postingEngine.postEntry(command);
        PostEntryResult second = postingEngine.postEntry(command);

        assertThat(first).isInstanceOf(PostEntryResult.Posted.class);
        assertThat(second).isInstanceOf(PostEntryResult.Posted.class);
        PostEntryResult.Posted firstPosted = (PostEntryResult.Posted) first;
        PostEntryResult.Posted secondPosted = (PostEntryResult.Posted) second;

        assertThat(secondPosted.entry().getId()).isEqualTo(firstPosted.entry().getId());
        assertThat(secondPosted.postings()).hasSameSizeAs(firstPosted.postings());
        assertThat(secondPosted.postings().stream().map(p -> p.getId()).toList())
                .containsExactlyInAnyOrderElementsOf(firstPosted.postings().stream().map(p -> p.getId()).toList());

        // Money moved exactly once, not twice.
        AccountBalance aliceBalance = accountBalanceRepository.findById(alice).orElseThrow();
        AccountBalance bobBalance = accountBalanceRepository.findById(bob).orElseThrow();
        assertThat(aliceBalance.getAvailableMinor()).isEqualTo(9_000);
        assertThat(bobBalance.getAvailableMinor()).isEqualTo(1_000);

        assertThat(postingRepository.findByEntryId(firstPosted.entry().getId())).hasSize(2);
    }

    @Test
    void replayDoesNotReRejectEvenIfBalanceWouldNowBeInsufficient() {
        // The first call succeeds while funds are available; a second call with the same key,
        // made after the money is gone, must still return the ORIGINAL successful result -
        // idempotent replay short-circuits before any balance re-check.
        UUID alice = openFundedWallet("EUR", 1_000);
        UUID bob = openFundedWallet("EUR", 0);
        String idempotencyKey = "payment:" + UUID.randomUUID();

        PostEntryCommand command = new PostEntryCommand(idempotencyKey, JournalEntryType.TRANSFER,
                UUID.randomUUID(),
                List.of(
                        new PostingLine(alice, PostingDirection.DEBIT, 1_000, "EUR", null),
                        new PostingLine(bob, PostingDirection.CREDIT, 1_000, "EUR", null)));

        PostEntryResult first = postingEngine.postEntry(command);
        assertThat(first).isInstanceOf(PostEntryResult.Posted.class);
        // Alice's available is now 0 - a fresh attempt at this same debit would be rejected.

        PostEntryResult replay = postingEngine.postEntry(command);
        assertThat(replay).isInstanceOf(PostEntryResult.Posted.class);
        assertThat(((PostEntryResult.Posted) replay).entry().getId())
                .isEqualTo(((PostEntryResult.Posted) first).entry().getId());
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
