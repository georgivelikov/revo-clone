package com.revolutclone.ledger.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.FxExecutionRequestedV1;
import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.domain.JournalEntryType;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.LedgerAccountType;
import com.revolutclone.ledger.domain.PostingDirection;
import com.revolutclone.ledger.domain.Reservation;
import com.revolutclone.ledger.domain.SystemAccountRefs;
import com.revolutclone.ledger.posting.PostEntryCommand;
import com.revolutclone.ledger.posting.PostEntryResult;
import com.revolutclone.ledger.posting.PostingEngine;
import com.revolutclone.ledger.posting.PostingLine;
import com.revolutclone.ledger.reservation.ReservationService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
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
 * plans/phase-04-fx-multicurrency.md's Tests table: "After 500 random exchanges, every currency
 * sums to 0", and the phase's own Verification query is the assertion, verbatim:
 *
 * <pre>
 * select currency, sum(case when direction='CREDIT' then amount_minor else -amount_minor end)
 * from posting group by currency;  -- every currency: exactly 0
 * </pre>
 *
 * <p>Multi-currency is what makes this different from phase 03's {@code ZeroSumAfterTransfersTest}.
 * A transfer's two legs are in one currency and cannot help but cancel; an exchange's legs are in
 * two, and the invariant only holds because each currency balances through the pool independently.
 * Five hundred exchanges across four currencies - including JPY, which has zero minor digits and
 * so catches anything that assumed two - is enough interleaving for a leg posted in the wrong
 * currency, an amount reused across sides, or a fee netted into the pool to show up as a non-zero
 * sum somewhere.
 *
 * <p><strong>The amounts are random, not the rates.</strong> Pricing is fx-service's job and is
 * proven there ({@code FxLegs}, {@code SpreadIsRevenueTest}); the ledger is handed four amounts per
 * exchange and its contribution is that they balance. So each exchange draws a random size, prices
 * the pool leg at a fixed integer rate for the pair, takes 50 bps of that as the fee, and forces a
 * residual of -1, 0 or +1 on a third of them - which exercises the sixth leg in both directions far
 * more often than a realistic rate would.
 *
 * <p>Balance conservation is checked alongside the sum, for the reason
 * {@code ZeroSumAfterTransfersTest} gives: postings that balance perfectly while the cached
 * balances drift is precisely the failure the capture path can cause, and it is invisible to the
 * headline query.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000",
        "revolut.ledger.reservation.sweep-delay-ms=300000"
})
class ZeroSumMultiCurrencyTest {

    private static final int EXCHANGES = 500;
    private static final long FUNDING_MINOR = 100_000_000L;
    private static final long MAX_SELL_MINOR = 20_000L;
    private static final int SPREAD_BPS = 50;

    private static final List<String> CURRENCIES = List.of("EUR", "GBP", "USD", "JPY");

    /**
     * Mid rates as integer ratios, so no decimal type is needed to price a leg here - CLAUDE.md's
     * hard rule 1 confines that to {@code FxMath}, and this test is not doing FX arithmetic, it is
     * generating plausible amounts. Keyed "SELL/BUY"; JPY's ratios are large on purpose.
     */
    private static final Map<String, long[]> MID_RATIOS = Map.ofEntries(
            Map.entry("EUR/GBP", new long[] {86, 100}), Map.entry("GBP/EUR", new long[] {100, 86}),
            Map.entry("EUR/USD", new long[] {108, 100}), Map.entry("USD/EUR", new long[] {100, 108}),
            Map.entry("GBP/USD", new long[] {126, 100}), Map.entry("USD/GBP", new long[] {100, 126}),
            Map.entry("EUR/JPY", new long[] {1625, 10}), Map.entry("JPY/EUR", new long[] {10, 1625}),
            Map.entry("GBP/JPY", new long[] {1890, 10}), Map.entry("JPY/GBP", new long[] {10, 1890}),
            Map.entry("USD/JPY", new long[] {1505, 10}), Map.entry("JPY/USD", new long[] {10, 1505}));

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void oidcIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://127.0.0.1:8180/realms/revolut");
    }

    @Autowired
    private FxExecutionRequestedEventHandler handler;
    @Autowired
    private ReservationService reservations;
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
    void everyCurrencySumsToZeroAfterFiveHundredRandomExchanges() throws SQLException {
        Map<String, UUID> wallets = new LinkedHashMap<>();
        for (String currency : CURRENCIES) {
            UUID wallet = openWallet(currency);
            fund(wallet, currency, FUNDING_MINOR);
            wallets.put(currency, wallet);
        }
        UUID userId = UUID.randomUUID();

        Random random = new Random(20260927L);
        int exchanged = 0;
        for (int i = 0; i < EXCHANGES; i++) {
            String sellCurrency = CURRENCIES.get(random.nextInt(CURRENCIES.size()));
            String buyCurrency = CURRENCIES.get((CURRENCIES.indexOf(sellCurrency) + 1
                    + random.nextInt(CURRENCIES.size() - 1)) % CURRENCIES.size());

            long sellMinor = 1L + random.nextLong(MAX_SELL_MINOR);
            long[] ratio = MID_RATIOS.get(sellCurrency + "/" + buyCurrency);
            long poolMidMinor = Math.max(1L, sellMinor * ratio[0] / ratio[1]);
            long feeMinor = poolMidMinor * SPREAD_BPS / 10_000L;
            // A forced residual on a third of the exchanges, in both directions: the sixth leg is
            // the easiest one to get wrong and the rarest to hit by chance.
            long pnlMinor = switch (i % 3) {
                case 1 -> 1L;
                case 2 -> -1L;
                default -> 0L;
            };
            long clientBuyMinor = poolMidMinor - feeMinor - pnlMinor;
            if (clientBuyMinor <= 0) {
                continue;
            }

            UUID quoteId = UUID.randomUUID();
            Reservation hold = reservations.hold(wallets.get(sellCurrency), sellMinor, sellCurrency, quoteId,
                    "fx-quote:" + quoteId, Instant.now().plus(Duration.ofSeconds(30)));
            handler.handle(envelope(new FxExecutionRequestedV1(quoteId, userId, hold.getId(),
                    wallets.get(buyCurrency), sellCurrency, sellMinor, buyCurrency, poolMidMinor,
                    clientBuyMinor, feeMinor, pnlMinor)));
            exchanged++;
        }

        long posted = countFxEntries();
        assertThat(posted).as("a run that posted nothing would prove nothing").isEqualTo(exchanged);
        assertThat(exchanged).isGreaterThan(EXCHANGES / 2);

        Map<String, Long> netByCurrency = queryNetPostingsByCurrency();
        for (String currency : CURRENCIES) {
            assertThat(netByCurrency.getOrDefault(currency, 0L))
                    .as("sum(postings) for " + currency + " after " + exchanged + " exchanges")
                    .isZero();
        }

        // Fee income is the reason the entry has five legs rather than four: after 500 exchanges at
        // 50 bps it cannot still be zero unless the pool leg was posted at the client rate.
        assertThat(CURRENCIES.stream().mapToLong(currency -> availableOf(systemAccount(
                        LedgerAccountType.FEE_INCOME, SystemAccountRefs.FEE_INCOME, currency))).sum())
                .as("the spread has to have landed somewhere, and FEE_INCOME is where")
                .isGreaterThan(0L);

        // Every minor unit is still accounted for: what the wallets hold plus what the pool, fee and
        // PnL accounts hold is exactly what was funded in, per currency.
        for (String currency : CURRENCIES) {
            long held = totalOf(wallets.get(currency))
                    + totalOf(systemAccount(LedgerAccountType.FX_POOL, SystemAccountRefs.FX_POOL, currency))
                    + totalOf(systemAccount(LedgerAccountType.FEE_INCOME, SystemAccountRefs.FEE_INCOME, currency))
                    + totalOf(systemAccount(LedgerAccountType.FX_PNL, SystemAccountRefs.FX_PNL, currency));
            assertThat(held).as("wallet + pool + fee + pnl for " + currency).isEqualTo(FUNDING_MINOR);
        }
    }

    private UUID openWallet(String currency) {
        return transactionTemplate.execute(status -> {
            LedgerAccount account = new LedgerAccount(UUID.randomUUID(), "user:" + UUID.randomUUID(),
                    LedgerAccountType.USER_WALLET, currency, UUID.randomUUID());
            ledgerAccounts.save(account);
            balances.save(new AccountBalance(account.getId(), currency));
            return account.getId();
        });
    }

    private void fund(UUID walletId, String currency, long amountMinor) {
        UUID clearing = systemAccount(LedgerAccountType.EXTERNAL_CLEARING, SystemAccountRefs.EXTERNAL_CLEARING,
                currency);
        PostEntryResult result = postingEngine.postEntry(new PostEntryCommand("fund:" + UUID.randomUUID(),
                JournalEntryType.ADJUSTMENT, UUID.randomUUID(), List.of(
                        new PostingLine(clearing, PostingDirection.DEBIT, amountMinor, currency, null),
                        new PostingLine(walletId, PostingDirection.CREDIT, amountMinor, currency, null))));
        assertThat(result).isInstanceOf(PostEntryResult.Posted.class);
    }

    private UUID systemAccount(LedgerAccountType type, String ownerRef, String currency) {
        return ledgerAccounts.findByOwnerRefAndTypeAndCurrency(ownerRef, type, currency)
                .map(LedgerAccount::getId)
                .orElseThrow(() -> new AssertionError("no seeded " + type + " account for " + currency));
    }

    private long availableOf(UUID ledgerAccountId) {
        return balances.findById(ledgerAccountId).orElseThrow().getAvailableMinor();
    }

    private long totalOf(UUID ledgerAccountId) {
        AccountBalance balance = balances.findById(ledgerAccountId).orElseThrow();
        return balance.getAvailableMinor() + balance.getReservedMinor();
    }

    private long countFxEntries() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select count(*) from journal_entry where type = 'FX'");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** The phase's Verification query, unchanged: CREDIT positive, DEBIT negative, grouped by currency. */
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

    private static EventEnvelope<FxExecutionRequestedV1> envelope(FxExecutionRequestedV1 payload) {
        return new EventEnvelope<>(UUID.randomUUID(), Instant.now(), "corr-" + payload.quoteId(),
                "FxExecutionRequestedV1", 1, payload);
    }
}
