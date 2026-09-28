package com.revolutclone.ledger.reservation;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.revolutclone.ledger.reservation.error.InsufficientAvailableBalanceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
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
 * plans/phase-05-cards.md's Tests table: <em>"ZeroSumAfterCardActivityTest - After 200 auths +
 * settlements, every currency sums to 0."</em>
 *
 * <p><strong>Why this lives in ledger-service rather than card-service.</strong> The invariant is about
 * postings, and postings only exist here; card-service's own settlement tests run against an in-memory
 * ledger, because {@code testcontainers-kafka} cannot start on this machine and so the two services
 * cannot be run side by side (docs/spring-boot-4-notes.md). Asserting zero-sum against a fake would be
 * asserting that the fake is self-consistent. So the workload is driven through the real
 * {@link ReservationService} and {@link CardSettlementService} against a real Postgres, exactly as
 * card-service's HTTP calls reach them - the one thing not exercised is the HTTP hop itself, which
 * cannot move money.
 *
 * <p><strong>All four settlement cases are in the mix, not just the happy one.</strong> Exact, partial,
 * over-settlement and reversal, plus a share of foreign-currency authorizations whose settlement splits
 * the credit side between {@code system:card-settlement} and {@code system:fee-income}. Each of those is
 * a different posting shape - two legs, two legs and a release, two entries, no entry at all, three legs
 * - and the reason to run them together is that the invariant has to hold across the mixture and not
 * merely within each case.
 *
 * <p>Three things are checked, and the second and third are the ones that catch what zero-sum cannot:
 * <ul>
 *   <li><strong>{@code sum(postings)} per currency is 0</strong> - the headline invariant, in the exact
 *       query shape from plans/phase-02-ledger.md's reconciliation section.</li>
 *   <li><strong>the balance cache agrees with the postings</strong> - {@code available + reserved}
 *       across the wallets equals what was funded in, less what was actually captured. A capture that
 *       decremented {@code available} as well as {@code reserved} would keep the postings perfectly
 *       balanced while quietly destroying money, which is precisely the failure
 *       {@code PostingEngine}'s balance-column-semantics table warns about and the reason a zero-sum
 *       test alone is not enough.</li>
 *   <li><strong>at least one wallet actually went negative</strong> - the over-settlement path is the
 *       one the phase says may legitimately overdraw, and a run in which it never did would be passing
 *       without having tested it.</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000",
        // The sweep would expire the holds this test deliberately leaves standing between the auth and
        // the settlement, turning a settlement into a "reservation is no longer HELD" failure.
        "revolut.ledger.reservation.sweep-delay-ms=300000"
})
class ZeroSumAfterCardActivityTest {

    private static final int AUTHORIZATIONS = 200;
    private static final int WALLETS_PER_CURRENCY = 3;
    /**
     * Deliberately small relative to the workload - about fifteen authorizations' worth per wallet
     * against roughly twenty-two attempts each. A richly funded wallet would never be overdrawn by an
     * over-settlement and never decline an authorization, so the two cases the invariant is most
     * interesting under would be absent while every assertion still passed.
     */
    private static final long FUNDING_MINOR = 60_000L;
    private static final long MAX_AUTH_MINOR = 8_000L;
    private static final List<String> CURRENCIES = List.of("EUR", "GBP", "USD");

    /** The card mark-up as a share of the billing amount on the foreign-currency authorizations. */
    private static final int MARKUP_BPS = 100;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void oidcIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://127.0.0.1:8180/realms/revolut");
    }

    @Autowired
    private ReservationService reservations;
    @Autowired
    private CardSettlementService cardSettlements;
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
    void perCurrencyPostingSumIsZeroAfterTwoHundredCardAuthorizationsAndTheirSettlements()
            throws SQLException {
        Map<String, List<UUID>> walletsByCurrency = openWallets();
        fundEveryWallet(walletsByCurrency);

        Random random = new Random(20260928L);
        Map<String, Long> capturedByCurrency = new HashMap<>();
        Map<Case, Integer> workload = new EnumMap<>(Case.class);
        int declined = 0;

        for (int i = 0; i < AUTHORIZATIONS; i++) {
            String currency = CURRENCIES.get(random.nextInt(CURRENCIES.size()));
            UUID wallet = walletsByCurrency.get(currency).get(random.nextInt(WALLETS_PER_CURRENCY));
            long billingMinor = 1L + random.nextLong(MAX_AUTH_MINOR);
            // A share of the traffic is a foreign-currency purchase, so its settlement carries the
            // three-leg shape with the mark-up split out to FEE_INCOME.
            long feeMinor = random.nextInt(3) == 0 ? billingMinor * MARKUP_BPS / 10_000L : 0L;
            Case settlementCase = Case.values()[random.nextInt(Case.values().length)];

            Reservation hold;
            try {
                // Step 6 of the authorization flow: HOLD the billing amount with a seven-day expiry.
                hold = reservations.hold(wallet, billingMinor, currency, UUID.randomUUID(),
                        "card-auth:net-" + i, Instant.now().plus(7, ChronoUnit.DAYS));
            } catch (InsufficientAvailableBalanceException ex) {
                // A declined authorization is part of the workload, not something to engineer away: a
                // wallet an earlier over-settlement pushed negative genuinely cannot cover the next tap,
                // and an authorization that wrote nothing at all is itself part of what keeps the sum
                // at zero. See OverSettlementTest, which asserts the same refusal from card-service.
                declined++;
                continue;
            }

            long settledMinor = switch (settlementCase) {
                case EXACT -> billingMinor;
                // At least one minor unit, so a "partial" is never accidentally a reversal.
                case PARTIAL -> 1L + random.nextLong(Math.max(billingMinor, 1L));
                case OVER -> billingMinor + 1L + random.nextLong(MAX_AUTH_MINOR);
                case REVERSAL -> 0L;
            };

            if (settlementCase == Case.REVERSAL) {
                reservations.release(hold.getId());
            } else {
                long capped = Math.min(feeMinor, Math.min(settledMinor, billingMinor));
                CardSettlementResult result = cardSettlements.settle(hold.getId(), settledMinor, capped,
                        "net-" + i);
                assertThat(result.capturedMinor() + result.excessMinor())
                        .as("the ledger's split of a " + settlementCase + " settlement must account for "
                                + "every minor unit of it")
                        .isEqualTo(settledMinor);
                capturedByCurrency.merge(currency, settledMinor, Long::sum);
            }
            workload.merge(settlementCase, 1, Integer::sum);
        }

        String summary = workload + ", declined=" + declined + " of " + AUTHORIZATIONS;
        for (Case settlementCase : Case.values()) {
            assertThat(workload.getOrDefault(settlementCase, 0))
                    .as("every settlement case has to actually occur or this run proves less than it "
                            + "claims: " + summary)
                    .isGreaterThan(0);
        }

        // The headline invariant. CLAUDE.md: "Any row returned is a hard failure regardless of what the
        // test suite says."
        Map<String, Long> netByCurrency = queryNetPostingsByCurrency();
        for (String currency : CURRENCIES) {
            assertThat(netByCurrency.getOrDefault(currency, 0L))
                    .as("sum(postings) for " + currency + " after " + summary)
                    .isZero();
        }

        // The balance cache has to agree with the postings, or money has quietly been destroyed in a way
        // the zero-sum check cannot see. Releases and partial remainders went back to `available`, so
        // what is left is exactly what was funded less what was genuinely captured.
        for (String currency : CURRENCIES) {
            long expected = FUNDING_MINOR * WALLETS_PER_CURRENCY - capturedByCurrency.getOrDefault(currency, 0L);
            long actual = walletsByCurrency.get(currency).stream()
                    .map(id -> balances.findById(id).orElseThrow())
                    .mapToLong(balance -> balance.getAvailableMinor() + balance.getReservedMinor())
                    .sum();
            assertThat(actual).as("available + reserved across " + currency + " wallets after " + summary)
                    .isEqualTo(expected);
        }

        // And the overdraw actually happened. plans/phase-05-cards.md: over-settlement "is the one path
        // that can legitimately push a wallet negative" - a run where no wallet did would be passing the
        // zero-sum assertion without having exercised the case that makes it interesting.
        long overdrawnWallets = walletsByCurrency.values().stream()
                .flatMap(List::stream)
                .map(id -> balances.findById(id).orElseThrow())
                .filter(balance -> balance.getAvailableMinor() < 0)
                .count();
        assertThat(overdrawnWallets)
                .as("at least one wallet should be overdrawn by an over-settlement after " + summary)
                .isGreaterThan(0L);

        // Every settlement is CARD_SETTLEMENT-typed, which is what phase 06's analytics projection will
        // filter on - and a settlement recorded under any other type would be invisible to it.
        assertThat(countEntries("CARD_SETTLEMENT"))
                .as("one entry per settlement, plus one more for each over-settlement's excess")
                .isEqualTo(workload.getOrDefault(Case.EXACT, 0) + workload.getOrDefault(Case.PARTIAL, 0)
                        + 2L * workload.getOrDefault(Case.OVER, 0));
    }

    /** The four rows of the phase's settlement table, as a workload to draw from. */
    private enum Case {
        EXACT, PARTIAL, OVER, REVERSAL
    }

    private Map<String, List<UUID>> openWallets() {
        Map<String, List<UUID>> byCurrency = new LinkedHashMap<>();
        for (String currency : CURRENCIES) {
            List<UUID> wallets = new ArrayList<>();
            for (int i = 0; i < WALLETS_PER_CURRENCY; i++) {
                wallets.add(openWallet(currency));
            }
            byCurrency.put(currency, wallets);
        }
        return byCurrency;
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

    /** Through real balanced entries against {@code system:external-clearing}, as task 02.11's path does. */
    private void fundEveryWallet(Map<String, List<UUID>> walletsByCurrency) {
        walletsByCurrency.forEach((currency, wallets) -> {
            LedgerAccount clearing = ledgerAccounts.findByOwnerRefAndTypeAndCurrency(
                            SystemAccountRefs.EXTERNAL_CLEARING, LedgerAccountType.EXTERNAL_CLEARING, currency)
                    .orElseThrow(() -> new AssertionError("no external-clearing account for " + currency));
            for (UUID wallet : wallets) {
                PostEntryResult result = postingEngine.postEntry(new PostEntryCommand(
                        "fund:" + UUID.randomUUID(), JournalEntryType.ADJUSTMENT, UUID.randomUUID(),
                        List.of(
                                new PostingLine(clearing.getId(), PostingDirection.DEBIT, FUNDING_MINOR,
                                        currency, null),
                                new PostingLine(wallet, PostingDirection.CREDIT, FUNDING_MINOR, currency,
                                        null))));
                assertThat(result).isInstanceOf(PostEntryResult.Posted.class);
            }
        });
    }

    private long countEntries(String type) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select count(*) from journal_entry where type = ?")) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
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
}
