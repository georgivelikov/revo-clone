package com.revolutclone.ledger.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.PaymentInitiatedV1;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import javax.sql.DataSource;
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
 * plans/phase-03-payments-and-ui.md's Tests table: "After 1000 random transfers, per-currency
 * posting sum is 0."
 *
 * <p>The transfers go in the way production ones do - 1000 {@code PaymentInitiatedV1} events
 * through {@link PaymentInitiatedEventHandler}, which resolves both accounts and drives
 * {@link PostingEngine} - so this exercises the consumer written in task 03.4 and not just the
 * posting engine phase 02's own {@code ZeroSumInvariantTest} already covers with raw SQL. Three
 * currencies, several wallets each, random pairs and random amounts from a fixed seed, so a
 * failure is reproducible rather than a one-off.
 *
 * <p>Two invariants are checked, and the second is the interesting one:
 * <ul>
 *   <li><strong>{@code sum(postings)} per currency is 0</strong> - the headline invariant, using
 *       the exact query shape from plans/phase-02-ledger.md's reconciliation section. Nothing in
 *       this suite is allowed to make it false.</li>
 *   <li><strong>{@code available + reserved} per currency still equals what was funded in</strong>
 *       - because the zero-sum check alone cannot see a balance-cache bug. Postings that balance
 *       perfectly while the cached balances drift is precisely the failure mode
 *       {@link PostingEngine}'s balance-column-semantics table warns about, and a transfer
 *       workload is the cheapest place to notice it.</li>
 * </ul>
 * Rejections are part of the workload rather than something to engineer away: a random amount
 * against a finite balance will sometimes overdraw, and a rejected entry writing nothing at all is
 * itself part of what keeps the sum at zero. The assertion message reports the posted/rejected
 * split so a run that rejected everything cannot pass by proving nothing.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000"
})
class ZeroSumAfterTransfersTest {

    private static final int TRANSFERS = 1_000;
    private static final int WALLETS_PER_CURRENCY = 4;
    private static final long FUNDING_MINOR = 1_000_000L;
    private static final long MAX_TRANSFER_MINOR = 5_000L;
    private static final List<String> CURRENCIES = List.of("EUR", "GBP", "USD");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void oidcIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://127.0.0.1:8180/realms/revolut");
    }

    @Autowired
    private PaymentInitiatedEventHandler handler;
    @Autowired
    private LedgerAccountRepository ledgerAccounts;
    @Autowired
    private AccountBalanceRepository balances;
    @Autowired
    private PostingEngine postingEngine;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private DataSource dataSource;

    @Test
    void perCurrencyPostingSumIsZeroAfterAThousandRandomTransfers() throws SQLException {
        Map<String, List<Wallet>> walletsByCurrency = openWallets();
        fundEveryWallet(walletsByCurrency);

        Random random = new Random(20260926L);
        for (int i = 0; i < TRANSFERS; i++) {
            String currency = CURRENCIES.get(random.nextInt(CURRENCIES.size()));
            List<Wallet> wallets = walletsByCurrency.get(currency);
            int senderIndex = random.nextInt(wallets.size());
            int receiverIndex = (senderIndex + 1 + random.nextInt(wallets.size() - 1)) % wallets.size();
            long amountMinor = 1L + random.nextLong(MAX_TRANSFER_MINOR);

            handler.handle(envelope(new PaymentInitiatedV1(UUID.randomUUID(),
                    wallets.get(senderIndex).externalAccountId(),
                    wallets.get(receiverIndex).externalAccountId(),
                    amountMinor, currency)));
        }

        // Each event either posted exactly one TRANSFER entry or wrote nothing at all, and every
        // payment id was distinct, so counting entries once at the end is exact - and cheaper than
        // two round trips per iteration.
        long posted = countTransferEntries();
        long rejected = TRANSFERS - posted;
        String workload = "posted=" + posted + ", rejected=" + rejected + " of " + TRANSFERS + " transfers";
        assertThat(posted).as("a run that posted nothing would prove nothing: " + workload).isGreaterThan(0L);
        assertThat(rejected).as("more rejections than transfers is impossible: " + workload)
                .isGreaterThanOrEqualTo(0L);

        Map<String, Long> netByCurrency = queryNetPostingsByCurrency();
        for (String currency : CURRENCIES) {
            assertThat(netByCurrency.getOrDefault(currency, 0L))
                    .as("sum(postings) for " + currency + " after " + workload)
                    .isZero();
        }

        // The balance cache has to agree with the postings, or money has quietly been destroyed in
        // a way the zero-sum check cannot see. Funding moved FUNDING_MINOR per wallet out of
        // system:external-clearing, whose own balance goes correspondingly negative - so the
        // customer-side total is what must still add up.
        for (String currency : CURRENCIES) {
            long expected = FUNDING_MINOR * WALLETS_PER_CURRENCY;
            long actual = walletsByCurrency.get(currency).stream()
                    .map(wallet -> balances.findById(wallet.ledgerAccountId()).orElseThrow())
                    .mapToLong(balance -> balance.getAvailableMinor() + balance.getReservedMinor())
                    .sum();
            assertThat(actual).as("available + reserved across " + currency + " wallets after " + workload)
                    .isEqualTo(expected);
        }
    }

    private Map<String, List<Wallet>> openWallets() {
        Map<String, List<Wallet>> byCurrency = new LinkedHashMap<>();
        for (String currency : CURRENCIES) {
            List<Wallet> wallets = new ArrayList<>();
            for (int i = 0; i < WALLETS_PER_CURRENCY; i++) {
                wallets.add(openWallet(currency));
            }
            byCurrency.put(currency, wallets);
        }
        return byCurrency;
    }

    private Wallet openWallet(String currency) {
        UUID externalAccountId = UUID.randomUUID();
        UUID ledgerAccountId = transactionTemplate.execute(status -> {
            LedgerAccount account = new LedgerAccount(UUID.randomUUID(), "user:" + UUID.randomUUID(),
                    LedgerAccountType.USER_WALLET, currency, externalAccountId);
            ledgerAccounts.save(account);
            balances.save(new AccountBalance(account.getId(), currency));
            return account.getId();
        });
        return new Wallet(externalAccountId, ledgerAccountId);
    }

    /**
     * Funds through real balanced entries against {@code system:external-clearing} rather than by
     * crediting the balance rows directly - the money has to enter the ledger the same way it does
     * in production (task 02.11's funding path), or the zero-sum assertion would be measuring a
     * workload that never touched a system account.
     */
    private void fundEveryWallet(Map<String, List<Wallet>> walletsByCurrency) {
        walletsByCurrency.forEach((currency, wallets) -> {
            LedgerAccount clearing = ledgerAccounts.findByOwnerRefAndTypeAndCurrency(
                            SystemAccountRefs.EXTERNAL_CLEARING, LedgerAccountType.EXTERNAL_CLEARING, currency)
                    .orElseThrow(() -> new AssertionError("no external-clearing account for " + currency));
            for (Wallet wallet : wallets) {
                PostEntryResult result = postingEngine.postEntry(new PostEntryCommand(
                        "fund:" + UUID.randomUUID(), JournalEntryType.ADJUSTMENT, UUID.randomUUID(),
                        List.of(
                                new PostingLine(clearing.getId(), PostingDirection.DEBIT, FUNDING_MINOR,
                                        currency, null),
                                new PostingLine(wallet.ledgerAccountId(), PostingDirection.CREDIT, FUNDING_MINOR,
                                        currency, null))));
                assertThat(result).isInstanceOf(PostEntryResult.Posted.class);
            }
        });
    }

    private long countTransferEntries() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select count(*) from journal_entry where type = 'TRANSFER'");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** The exact query shape from plans/phase-02-ledger.md: CREDIT positive, DEBIT negative. */
    private Map<String, Long> queryNetPostingsByCurrency() throws SQLException {
        Map<String, Long> result = new HashMap<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select currency, "
                                + "sum(case when direction = 'CREDIT' then amount_minor else -amount_minor end) as net "
                                + "from posting group by currency");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.put(rs.getString("currency").trim(), rs.getLong("net"));
            }
        }
        return result;
    }

    private static EventEnvelope<PaymentInitiatedV1> envelope(PaymentInitiatedV1 payload) {
        return new EventEnvelope<>(UUID.randomUUID(), Instant.now(), "corr-" + payload.paymentId(),
                "PaymentInitiatedV1", 1, payload);
    }

    /** An account-service account id paired with the ledger account it resolves to. */
    private record Wallet(UUID externalAccountId, UUID ledgerAccountId) {
    }
}
