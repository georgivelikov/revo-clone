package com.revolutclone.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.domain.JournalEntry;
import com.revolutclone.ledger.domain.JournalEntryRepository;
import com.revolutclone.ledger.domain.JournalEntryType;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.LedgerAccountType;
import com.revolutclone.ledger.domain.PostingDirection;
import com.revolutclone.ledger.domain.SystemAccountRefs;
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
 * plans/phase-04-fx-multicurrency.md's task 7: "Ledger: {@code FX} entry type, pool/fee/PnL
 * accounts per currency, negative-balance allowance for system accounts." This proves the two
 * pieces of that not already pinned down elsewhere, each in isolation from the FX quote-execution
 * pipeline that otherwise exercises them only as a side effect:
 *
 * <ul>
 *   <li>{@link JournalEntryType#FX} is an entry type {@link PostingTransactionExecutor} actually
 *       posts and records - not just an enum constant that happens to compile.
 *   <li>A {@code DEBIT} that would leave a {@link LedgerAccountType#FX_POOL} account negative is
 *       posted anyway, where the identical DEBIT against a {@code USER_WALLET} in the same entry
 *       shape is rejected - {@link PostingTransactionExecutor#validateLine}'s
 *       {@code isSystemAccount()} exemption, proven against a real posted entry rather than read
 *       off the source.
 * </ul>
 *
 * <p>"Pool/fee/PnL accounts per currency" - the third leg of the task title - is already fully
 * proven by {@code SystemAccountBootstrapTest} (task 02.7, which seeded them ahead of this phase
 * precisely so it would not need redoing here): every system account type exists for every
 * supported currency at a zero starting balance. This test reuses those same seeded rows rather
 * than minting new ones, which is also why balances are asserted as deltas - the pool is shared
 * by every test in the suite that exchanges through it, exactly as {@code FxEntryBalanceTest}
 * documents.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class FxEntryTypeAndSystemAccountBalanceTest {

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
    private TransactionTemplate transactionTemplate;

    @Test
    void aDebitBeyondAvailableIsPostedAgainstTheFxPoolButRejectedAgainstAUserWallet() {
        UUID pool = systemAccount(LedgerAccountType.FX_POOL, SystemAccountRefs.FX_POOL, "EUR");
        UUID feeIncome = systemAccount(LedgerAccountType.FEE_INCOME, SystemAccountRefs.FEE_INCOME, "EUR");
        long poolBefore = availableOf(pool);

        // The pool debits an amount it does not have available - exactly what an FX book does on
        // every exchange, since it starts long the currency it is about to give away none of yet.
        PostEntryResult poolResult = postingEngine.postEntry(new PostEntryCommand(
                "fxquote:" + UUID.randomUUID(), JournalEntryType.FX, UUID.randomUUID(),
                List.of(
                        new PostingLine(pool, PostingDirection.DEBIT, 1_000_000, "EUR", null),
                        new PostingLine(feeIncome, PostingDirection.CREDIT, 1_000_000, "EUR", null))));

        assertThat(poolResult).isInstanceOf(PostEntryResult.Posted.class);
        JournalEntry posted = ((PostEntryResult.Posted) poolResult).entry();
        assertThat(posted.getType()).isEqualTo(JournalEntryType.FX);
        // Not just an in-memory return value: re-read from journal_entry (a plain `text` column,
        // per V3__ledger.sql) round-trips FX correctly through the enum mapping.
        assertThat(journalEntryRepository.findById(posted.getId()).orElseThrow().getType())
                .isEqualTo(JournalEntryType.FX);
        assertThat(availableOf(pool) - poolBefore).isEqualTo(-1_000_000);
        assertThat(availableOf(pool)).isNegative();

        // The identical shape against a freshly-opened, empty USER_WALLET is refused instead - the
        // rule is "system accounts", not "this entry type" or "this account happened to be able to
        // afford it".
        UUID wallet = openEmptyWallet("EUR");
        PostEntryResult walletResult = postingEngine.postEntry(new PostEntryCommand(
                "fxquote:" + UUID.randomUUID(), JournalEntryType.FX, UUID.randomUUID(),
                List.of(
                        new PostingLine(wallet, PostingDirection.DEBIT, 1_000_000, "EUR", null),
                        new PostingLine(feeIncome, PostingDirection.CREDIT, 1_000_000, "EUR", null))));

        assertThat(walletResult).isInstanceOf(PostEntryResult.Rejected.class);
        assertThat(((PostEntryResult.Rejected) walletResult).reason())
                .isEqualTo(EntryRejectionReason.INSUFFICIENT_FUNDS);
        assertThat(availableOf(wallet)).isZero();
    }

    private UUID systemAccount(LedgerAccountType type, String ownerRef, String currency) {
        return ledgerAccountRepository.findByOwnerRefAndTypeAndCurrency(ownerRef, type, currency)
                .orElseThrow(() -> new IllegalStateException(
                        "system account bootstrap (task 02.7) did not seed " + type + "/" + currency))
                .getId();
    }

    private UUID openEmptyWallet(String currency) {
        return transactionTemplate.execute(status -> {
            LedgerAccount account = new LedgerAccount(UUID.randomUUID(), "user:" + UUID.randomUUID(),
                    LedgerAccountType.USER_WALLET, currency);
            ledgerAccountRepository.save(account);
            accountBalanceRepository.save(new AccountBalance(account.getId(), currency));
            return account.getId();
        });
    }

    private long availableOf(UUID ledgerAccountId) {
        return accountBalanceRepository.findById(ledgerAccountId).orElseThrow().getAvailableMinor();
    }
}
