package com.revolutclone.ledger.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.FxExecutionRequestedV1;
import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.domain.JournalEntry;
import com.revolutclone.ledger.domain.JournalEntryRepository;
import com.revolutclone.ledger.domain.JournalEntryType;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.LedgerAccountType;
import com.revolutclone.ledger.domain.Posting;
import com.revolutclone.ledger.domain.PostingDirection;
import com.revolutclone.ledger.domain.PostingRepository;
import com.revolutclone.ledger.domain.Reservation;
import com.revolutclone.ledger.domain.ReservationRepository;
import com.revolutclone.ledger.domain.ReservationState;
import com.revolutclone.ledger.domain.SystemAccountRefs;
import com.revolutclone.ledger.posting.PostEntryCommand;
import com.revolutclone.ledger.posting.PostEntryResult;
import com.revolutclone.ledger.posting.PostingEngine;
import com.revolutclone.ledger.posting.PostingLine;
import com.revolutclone.ledger.reservation.ReservationService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * plans/phase-04-fx-multicurrency.md's Tests table: "FX entry nets to zero <b>per currency</b>,
 * across all five or six legs."
 *
 * <p>The phase's own worked shape, driven through {@link FxExecutionRequestedEventHandler} exactly
 * as a real execution arrives: Alice sells 100.00 EUR at a mid of 0.86 with a 50 bps spread, the
 * pool gives up the mid amount, Alice is paid the client amount and the difference is revenue.
 * What is asserted here is what the phase says the entry <em>is</em>, and each assertion is a
 * failure mode it names:
 *
 * <ul>
 *   <li><b>Both currencies net to zero independently.</b> Summed over the whole entry across
 *       currencies the number would be meaningless - 10000 EUR against 8600 GBP is not a
 *       balance - so the invariant is per currency or it is nothing.</li>
 *   <li><b>One entry, not two.</b> Money leaves EUR and appears in GBP in a single transaction, so
 *       there is no partial state for a saga to compensate inside the ledger.</li>
 *   <li><b>The pool leg is the mid amount and the fee leg exists.</b> "FEE_INCOME staying at zero
 *       means you posted the pool leg at the client rate" - the entry would still balance, and
 *       every balancing test would still pass, with the spread silently sitting in pool drift.</li>
 *   <li><b>The pool does not net to zero in value.</b> Long EUR, short GBP: "if it does, you have
 *       almost certainly posted the conversion as a single two-leg entry".</li>
 *   <li><b>Alice's EUR is reduced exactly once.</b> The sell leg captures a hold, so it must
 *       decrement {@code reserved} and leave {@code available} alone - the doubly-reduced-EUR bug
 *       the phase points at, which the zero-sum check cannot see.</li>
 * </ul>
 *
 * <p>The pricing itself is fx-service's ({@code FxLegs}, {@code SpreadIsRevenueTest}); the event
 * carries the four amounts and this side is asked only to post them and to balance. The residual
 * case is driven by handing over a deliberately non-cancelling set, since whether the roundings
 * cancel is a property of the rate, not of the ledger.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000",
        "revolut.ledger.reservation.sweep-delay-ms=300000"
})
class FxEntryBalanceTest {

    /** 100.00 EUR sold, at mid 0.86 with 50 bps of spread - the phase's worked example. */
    private static final long SELL_EUR = 10_000L;
    private static final long POOL_MID_GBP = 8_600L;
    private static final long CLIENT_GBP = 8_557L;
    private static final long FEE_GBP = 43L;

    private static final long FUNDING_EUR = 50_000L;

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
    private ReservationRepository reservationRepository;
    @Autowired
    private LedgerAccountRepository ledgerAccounts;
    @Autowired
    private AccountBalanceRepository balances;
    @Autowired
    private JournalEntryRepository journalEntries;
    @Autowired
    private PostingRepository postings;
    @Autowired
    private PostingEngine postingEngine;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void theFxEntryIsFiveLegsAndNetsToZeroInEachCurrencySeparately() {
        Alice alice = anAliceWithEuros();
        UUID quoteId = UUID.randomUUID();

        handler.handle(envelope(execution(quoteId, alice, SELL_EUR, POOL_MID_GBP, CLIENT_GBP, FEE_GBP, 0L)));

        List<Posting> legs = legsOf(quoteId);
        assertThat(legs).hasSize(5);
        assertThat(netOf(legs, "EUR")).as("the sell currency nets to zero on its own").isZero();
        assertThat(netOf(legs, "GBP")).as("and so does the buy currency").isZero();
    }

    @Test
    void itIsOneJournalEntryOfTypeFxAcrossBothCurrencies() {
        Alice alice = anAliceWithEuros();
        UUID quoteId = UUID.randomUUID();

        handler.handle(envelope(execution(quoteId, alice, SELL_EUR, POOL_MID_GBP, CLIENT_GBP, FEE_GBP, 0L)));

        JournalEntry entry = journalEntries.findByIdempotencyKey("fxquote:" + quoteId).orElseThrow();
        assertThat(entry.getType()).isEqualTo(JournalEntryType.FX);
        assertThat(entry.getReferenceId()).isEqualTo(quoteId);
        // Every leg belongs to that one entry: an exchange split into two entries would leave a
        // moment where the EUR had gone and the GBP had not arrived.
        assertThat(legsOf(quoteId)).allSatisfy(leg -> assertThat(leg.getEntryId()).isEqualTo(entry.getId()));
    }

    @Test
    void thePoolGivesUpTheMidAmountAndTheSpreadIsPostedAsRevenue() {
        Alice alice = anAliceWithEuros();
        UUID quoteId = UUID.randomUUID();

        handler.handle(envelope(execution(quoteId, alice, SELL_EUR, POOL_MID_GBP, CLIENT_GBP, FEE_GBP, 0L)));

        Map<UUID, Posting> byAccount = legsOf(quoteId).stream()
                .collect(Collectors.toMap(Posting::getLedgerAccountId, Function.identity()));

        Posting poolGbp = byAccount.get(systemAccount(LedgerAccountType.FX_POOL, SystemAccountRefs.FX_POOL, "GBP"));
        assertThat(poolGbp.getDirection()).isEqualTo(PostingDirection.DEBIT);
        assertThat(poolGbp.getAmountMinor()).as("the pool leg is the MID amount, not the client amount")
                .isEqualTo(POOL_MID_GBP);

        Posting fee = byAccount.get(systemAccount(LedgerAccountType.FEE_INCOME, SystemAccountRefs.FEE_INCOME, "GBP"));
        assertThat(fee.getDirection()).isEqualTo(PostingDirection.CREDIT);
        assertThat(fee.getAmountMinor()).as("FEE_INCOME staying at zero would mean the pool leg was "
                + "posted at the client rate and the spread vanished into pool drift").isEqualTo(FEE_GBP);

        Posting client = byAccount.get(alice.gbpWallet());
        assertThat(client.getDirection()).isEqualTo(PostingDirection.CREDIT);
        assertThat(client.getAmountMinor()).as("the client is paid at the CLIENT rate").isEqualTo(CLIENT_GBP);
    }

    @Test
    void thePoolIsLeftLongTheSellCurrencyAndShortTheBuyCurrency() {
        Alice alice = anAliceWithEuros();
        UUID poolEur = systemAccount(LedgerAccountType.FX_POOL, SystemAccountRefs.FX_POOL, "EUR");
        UUID poolGbp = systemAccount(LedgerAccountType.FX_POOL, SystemAccountRefs.FX_POOL, "GBP");
        UUID feeGbp = systemAccount(LedgerAccountType.FEE_INCOME, SystemAccountRefs.FEE_INCOME, "GBP");
        // Deltas, not absolutes: the system accounts are seeded once per database and every test in
        // this class exchanges through the same pool, which is exactly what they are for.
        long poolEurBefore = availableOf(poolEur);
        long poolGbpBefore = availableOf(poolGbp);
        long feeBefore = availableOf(feeGbp);

        handler.handle(envelope(execution(UUID.randomUUID(), alice, SELL_EUR, POOL_MID_GBP, CLIENT_GBP,
                FEE_GBP, 0L)));

        // "Don't let the FX pool net to zero" - an FX book is long one currency and short the other,
        // and a pool that came out flat would mean the conversion was posted as a two-leg entry.
        assertThat(availableOf(poolEur) - poolEurBefore).isEqualTo(SELL_EUR);
        assertThat(availableOf(poolGbp) - poolGbpBefore).isEqualTo(-POOL_MID_GBP);
        assertThat(availableOf(feeGbp) - feeBefore).isEqualTo(FEE_GBP);
    }

    @Test
    void aliceLosesTheSellAmountExactlyOnceAndGainsTheClientAmount() {
        Alice alice = anAliceWithEuros();

        handler.handle(envelope(execution(UUID.randomUUID(), alice, SELL_EUR, POOL_MID_GBP, CLIENT_GBP,
                FEE_GBP, 0L)));

        AccountBalance eur = balances.findById(alice.eurWallet()).orElseThrow();
        // The hold already took the money out of `available` at quote time; the capturing DEBIT must
        // decrement `reserved` only. Both columns reduced would be the doubly-reduced-EUR bug, and
        // the postings would still balance perfectly while it happened.
        assertThat(eur.getAvailableMinor()).isEqualTo(FUNDING_EUR - SELL_EUR);
        assertThat(eur.getReservedMinor()).isZero();
        assertThat(availableOf(alice.gbpWallet())).isEqualTo(CLIENT_GBP);
        assertThat(reservationRepository.findById(alice.reservationId()).orElseThrow().getState())
                .isEqualTo(ReservationState.CAPTURED);
    }

    @Test
    void theSellLegCarriesTheReservationAndNoOtherLegDoes() {
        Alice alice = anAliceWithEuros();
        UUID quoteId = UUID.randomUUID();

        handler.handle(envelope(execution(quoteId, alice, SELL_EUR, POOL_MID_GBP, CLIENT_GBP, FEE_GBP, 0L)));

        assertThat(legsOf(quoteId)).filteredOn(leg -> leg.getReservationId() != null)
                .singleElement()
                .satisfies(capture -> {
                    assertThat(capture.getLedgerAccountId()).isEqualTo(alice.eurWallet());
                    assertThat(capture.getDirection()).isEqualTo(PostingDirection.DEBIT);
                    assertThat(capture.getAmountMinor()).isEqualTo(SELL_EUR);
                    assertThat(capture.getReservationId()).isEqualTo(alice.reservationId());
                });
    }

    @Test
    void aRoundingResidualAddsASixthLegAndTheCurrencyStillNetsToZero() {
        Alice alice = anAliceWithEuros();
        UUID quoteId = UUID.randomUUID();

        UUID pnlGbp = systemAccount(LedgerAccountType.FX_PNL, SystemAccountRefs.FX_PNL, "GBP");
        long pnlBefore = availableOf(pnlGbp);

        // The three roundings did not cancel: the pool gave up one minor unit more than the client
        // and the fee together. That unit is FX_PNL's, and nowhere else's.
        handler.handle(envelope(execution(quoteId, alice, SELL_EUR, POOL_MID_GBP, CLIENT_GBP - 1, FEE_GBP, 1L)));

        List<Posting> legs = legsOf(quoteId);
        assertThat(legs).hasSize(6);
        assertThat(netOf(legs, "EUR")).isZero();
        assertThat(netOf(legs, "GBP")).isZero();
        assertThat(availableOf(pnlGbp) - pnlBefore).isEqualTo(1L);
    }

    @Test
    void aNegativeResidualIsPostedAsADebitToPnlRatherThanNettedIntoTheFee() {
        Alice alice = anAliceWithEuros();
        UUID quoteId = UUID.randomUUID();

        UUID pnlGbp = systemAccount(LedgerAccountType.FX_PNL, SystemAccountRefs.FX_PNL, "GBP");
        UUID feeGbp = systemAccount(LedgerAccountType.FEE_INCOME, SystemAccountRefs.FEE_INCOME, "GBP");
        long pnlBefore = availableOf(pnlGbp);
        long feeBefore = availableOf(feeGbp);

        handler.handle(envelope(execution(quoteId, alice, SELL_EUR, POOL_MID_GBP, CLIENT_GBP, FEE_GBP + 1, -1L)));

        assertThat(legsOf(quoteId)).hasSize(6);
        assertThat(netOf(legsOf(quoteId), "GBP")).isZero();
        assertThat(availableOf(pnlGbp) - pnlBefore).isEqualTo(-1L);
        // The fee is whatever fx-service priced it at, never a plug that absorbs the residual: that
        // is the distinction FEE_INCOME exists to keep answerable.
        assertThat(availableOf(feeGbp) - feeBefore).isEqualTo(FEE_GBP + 1);
    }

    @Test
    void anExecutionWhoseHoldHasAlreadyGoneIsRejectedRatherThanPosted() {
        Alice alice = anAliceWithEuros();
        UUID quoteId = UUID.randomUUID();
        reservations.release(alice.reservationId());

        handler.handle(envelope(execution(quoteId, alice, SELL_EUR, POOL_MID_GBP, CLIENT_GBP, FEE_GBP, 0L)));

        // "Reservation expiry racing execution": the hold went before the capture reached it, so
        // there is nothing to capture and nothing to compensate - the funds are already back.
        assertThat(journalEntries.findByIdempotencyKey("fxquote:" + quoteId)).isEmpty();
        assertThat(availableOf(alice.eurWallet())).isEqualTo(FUNDING_EUR);
        assertThat(availableOf(alice.gbpWallet())).isZero();
    }

    @Test
    void anExecutionNamingAnUnknownWalletToCreditIsRejectedAndMovesNothing() {
        Alice alice = anAliceWithEuros();
        UUID quoteId = UUID.randomUUID();
        FxExecutionRequestedV1 event = new FxExecutionRequestedV1(quoteId, alice.userId(),
                alice.reservationId(), UUID.randomUUID(), "EUR", SELL_EUR, "GBP", POOL_MID_GBP,
                CLIENT_GBP, FEE_GBP, 0L);

        handler.handle(envelope(event));

        assertThat(journalEntries.findByIdempotencyKey("fxquote:" + quoteId)).isEmpty();
        assertThat(reservationRepository.findById(alice.reservationId()).orElseThrow().getState())
                .isEqualTo(ReservationState.HELD);
    }

    private FxExecutionRequestedV1 execution(UUID quoteId, Alice alice, long sellMinor, long poolMidMinor,
            long clientBuyMinor, long feeMinor, long pnlMinor) {
        return new FxExecutionRequestedV1(quoteId, alice.userId(), alice.reservationId(), alice.gbpWallet(),
                "EUR", sellMinor, "GBP", poolMidMinor, clientBuyMinor, feeMinor, pnlMinor);
    }

    /** A funded EUR wallet, an empty GBP wallet, and a hold over the sell amount - as quoting leaves it. */
    private Alice anAliceWithEuros() {
        UUID userId = UUID.randomUUID();
        UUID eurWallet = openWallet("EUR");
        UUID gbpWallet = openWallet("GBP");
        fund(eurWallet, "EUR", FUNDING_EUR);
        Reservation hold = reservations.hold(eurWallet, SELL_EUR, "EUR", UUID.randomUUID(),
                "fx-quote:" + UUID.randomUUID(), Instant.now().plus(Duration.ofSeconds(30)));
        return new Alice(userId, eurWallet, gbpWallet, hold.getId());
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

    /** Through a real balanced entry against external-clearing, the way money actually enters the ledger. */
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

    private List<Posting> legsOf(UUID quoteId) {
        Optional<JournalEntry> entry = journalEntries.findByIdempotencyKey("fxquote:" + quoteId);
        return entry.map(found -> postings.findByEntryId(found.getId())).orElse(List.of());
    }

    /** CREDIT positive, DEBIT negative - the phase's own verification expression, one currency at a time. */
    private static long netOf(List<Posting> legs, String currency) {
        return legs.stream()
                .filter(leg -> leg.getCurrency().equals(currency))
                .mapToLong(leg -> leg.getDirection() == PostingDirection.CREDIT
                        ? leg.getAmountMinor() : -leg.getAmountMinor())
                .sum();
    }

    private static EventEnvelope<FxExecutionRequestedV1> envelope(FxExecutionRequestedV1 payload) {
        return new EventEnvelope<>(UUID.randomUUID(), Instant.now(), "corr-" + payload.quoteId(),
                "FxExecutionRequestedV1", 1, payload);
    }

    private record Alice(UUID userId, UUID eurWallet, UUID gbpWallet, UUID reservationId) {
    }
}
