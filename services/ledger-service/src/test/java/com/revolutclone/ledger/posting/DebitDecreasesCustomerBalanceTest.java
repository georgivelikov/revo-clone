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
 * plans/phase-02-ledger.md's ### Accounting sign convention, asserted explicitly, by name, per
 * the phase's own instruction: "name the test DebitDecreasesCustomerBalanceTest". A transfer
 * from Alice to Bob is DEBIT alice_wallet / CREDIT bob_wallet - Alice's available balance goes
 * down by exactly the amount, Bob's goes up by exactly the amount, and nothing else.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DebitDecreasesCustomerBalanceTest {

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
    void debitDecreasesAndCreditIncreasesByExactlyTheAmount() {
        UUID alice = openFundedWallet("EUR", 10_000);
        UUID bob = openFundedWallet("EUR", 2_500);

        PostEntryResult result = postingEngine.postEntry(new PostEntryCommand(
                "payment:" + UUID.randomUUID(), JournalEntryType.TRANSFER, UUID.randomUUID(),
                List.of(
                        new PostingLine(alice, PostingDirection.DEBIT, 1_000, "EUR", null),
                        new PostingLine(bob, PostingDirection.CREDIT, 1_000, "EUR", null))));

        assertThat(result).isInstanceOf(PostEntryResult.Posted.class);

        AccountBalance aliceBalance = accountBalanceRepository.findById(alice).orElseThrow();
        AccountBalance bobBalance = accountBalanceRepository.findById(bob).orElseThrow();

        assertThat(aliceBalance.getAvailableMinor()).isEqualTo(9_000); // 10_000 - 1_000
        assertThat(bobBalance.getAvailableMinor()).isEqualTo(3_500); // 2_500 + 1_000
        assertThat(aliceBalance.getReservedMinor()).isZero();
        assertThat(bobBalance.getReservedMinor()).isZero();
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
