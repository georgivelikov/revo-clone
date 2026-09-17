package com.revolutclone.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.domain.JournalEntryType;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.LedgerAccountType;
import com.revolutclone.ledger.domain.PostingDirection;
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
 * plans/phase-02-ledger.md's ### Tests table: "An entry touching two currencies balances
 * per-currency, not overall." A single journal_entry may legitimately span currencies (the FX
 * entry in Phase 04 is the canonical example); each currency present must independently sum
 * to zero, not the entry's numeric total across currencies (which is meaningless - EUR minor
 * units and USD minor units are not fungible quantities to sum together).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MultiCurrencyEntryTest {

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
    private TransactionTemplate transactionTemplate;

    @Test
    void entryBalancedPerCurrencyIsAccepted() {
        UUID eurFrom = openFundedWallet("EUR", 5_000);
        UUID eurTo = openFundedWallet("EUR", 0);
        UUID usdFrom = openFundedWallet("USD", 3_000);
        UUID usdTo = openFundedWallet("USD", 0);

        // EUR leg moves 20.00, USD leg moves 12.34 - deliberately different numeric amounts,
        // since the point is that EACH currency's own postings sum to zero, not that the
        // entry's grand total (which would be a meaningless cross-currency sum) does.
        PostEntryResult result = postingEngine.postEntry(new PostEntryCommand(
                "fxquote:" + UUID.randomUUID(), JournalEntryType.FX, UUID.randomUUID(),
                List.of(
                        new PostingLine(eurFrom, PostingDirection.DEBIT, 2_000, "EUR", null),
                        new PostingLine(eurTo, PostingDirection.CREDIT, 2_000, "EUR", null),
                        new PostingLine(usdFrom, PostingDirection.DEBIT, 1_234, "USD", null),
                        new PostingLine(usdTo, PostingDirection.CREDIT, 1_234, "USD", null))));

        assertThat(result).isInstanceOf(PostEntryResult.Posted.class);

        assertThat(accountBalanceRepository.findById(eurFrom).orElseThrow().getAvailableMinor()).isEqualTo(3_000);
        assertThat(accountBalanceRepository.findById(eurTo).orElseThrow().getAvailableMinor()).isEqualTo(2_000);
        assertThat(accountBalanceRepository.findById(usdFrom).orElseThrow().getAvailableMinor()).isEqualTo(1_766);
        assertThat(accountBalanceRepository.findById(usdTo).orElseThrow().getAvailableMinor()).isEqualTo(1_234);
    }

    @Test
    void entryBalancedOverallButNotPerCurrencyIsRejected() {
        // 2000 EUR minor units debited but only 1000 USD minor units credited - the numeric
        // total nets to a positive 1000 if currencies were (wrongly) summed together, but
        // neither currency balances on its own, so PostingEngine must reject this before
        // writing anything, per-currency, exactly like the database trigger does.
        UUID eurFrom = openFundedWallet("EUR", 5_000);
        UUID usdTo = openFundedWallet("USD", 0);

        try {
            postingEngine.postEntry(new PostEntryCommand(
                    "fxquote:" + UUID.randomUUID(), JournalEntryType.FX, UUID.randomUUID(),
                    List.of(
                            new PostingLine(eurFrom, PostingDirection.DEBIT, 2_000, "EUR", null),
                            new PostingLine(usdTo, PostingDirection.CREDIT, 1_000, "USD", null))));
            org.assertj.core.api.Assertions.fail("expected IllegalArgumentException for an entry unbalanced per-currency");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("not balanced");
        }

        // Neither leg's balance moved.
        assertThat(accountBalanceRepository.findById(eurFrom).orElseThrow().getAvailableMinor()).isEqualTo(5_000);
        assertThat(accountBalanceRepository.findById(usdTo).orElseThrow().getAvailableMinor()).isZero();
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
