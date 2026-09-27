package com.revolutclone.fx.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.fx.rate.FxRateCache;
import com.revolutclone.fx.readmodel.AccountOwner;
import com.revolutclone.fx.readmodel.AccountOwnerRepository;
import com.revolutclone.fx.readmodel.LedgerAccountRef;
import com.revolutclone.fx.readmodel.LedgerAccountRefRepository;
import com.revolutclone.fx.testsupport.FakeLedgerReservations;
import com.revolutclone.fx.testsupport.FakeLedgerTestConfig;
import com.revolutclone.fx.testsupport.NoOpJwtDecoderTestConfig;
import com.revolutclone.fx.web.FxQuoteController;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * plans/phase-04-fx-multicurrency.md's Tests table: "Two quotes against one balance: second fails on
 * insufficient available."
 *
 * <p>That single line is the whole justification for step 4 of the quoting sequence. A quote that only
 * locked a rate would issue both of these happily, and the phase says what that costs: "without it, a
 * user can take five quotes against one balance and execute them all." So what is asserted here is not
 * the arithmetic - {@code SpreadIsRevenueTest} has the arithmetic - but that issuing a quote genuinely
 * <em>consumes</em> available funds, and that the refusal lands at quote time rather than at execution
 * time, after the user has already been shown a firm rate.
 *
 * <p><strong>Against a fake ledger, deliberately.</strong> {@link FakeLedgerReservations} enforces the
 * one rule this behaviour depends on ({@code available -= amount}, refuse below zero); that the real
 * ledger enforces it against real balance rows is ledger-service's own
 * {@code ReservedFundsAreNotAvailableTest} and {@code InsufficientFundsTest}. Restating it here would
 * test the ledger twice and fx-service not at all - fx-service's contribution is that it <em>asks
 * before issuing</em> and turns the answer into a 409 instead of writing a quote anyway. Postgres is
 * real, because the other half of this task is the {@code fx_quote} row itself and its two
 * {@code numeric(20,10)} rate columns.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import({NoOpJwtDecoderTestConfig.class, FakeLedgerTestConfig.class})
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000",
        // Never called - the ledger port is substituted by FakeLedgerTestConfig - but it still has to
        // bind, because application.yml takes it from an environment variable with no default.
        "revolut.fx.ledger.base-url=http://ledger.invalid",
        // The phase's own defaults, pinned rather than inherited so the numbers below stay readable if
        // the demo-time spread is ever turned up.
        "revolut.fx.quote.spread-bps=50",
        "revolut.fx.quote.ttl=30s"
})
class QuoteReservesFundsTest {

    /** 100.00 EUR - the phase's worked example, and the only money Alice has. */
    private static final long ALICE_EUR_BALANCE = 10_000L;

    private static final String EUR_GBP_MID = "0.8600000000";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeLedgerReservations ledger;

    @Autowired
    private FxRateCache rates;

    @Autowired
    private FxQuoteRepository quotes;

    @Autowired
    private AccountOwnerRepository accountOwners;

    @Autowired
    private LedgerAccountRefRepository ledgerAccountRefs;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private JsonMapper jsonMapper;

    private UUID alice;
    private UUID aliceEurLedgerAccount;

    @BeforeEach
    void aliceHasOneHundredEurosAndTheRateIsKnown() {
        jdbcClient.sql("delete from fx_quote").update();
        accountOwners.deleteAll();
        ledgerAccountRefs.deleteAll();
        ledger.reset();

        alice = UUID.randomUUID();
        aliceEurLedgerAccount = seedWallet(alice, "EUR");
        seedWallet(alice, "GBP");
        ledger.fund(aliceEurLedgerAccount, ALICE_EUR_BALANCE);

        rates.update(new FxRateCache.LatestRate("EUR/GBP", EUR_GBP_MID, Instant.now(), "test"));
    }

    @Test
    void theSecondOfTwoQuotesAgainstOneBalanceIsRefusedOnInsufficientAvailable() throws Exception {
        quoteBySellAmount(6_000L, null).andExpect(status().isCreated());

        // Same balance, same currency, a fresh idempotency key - so this is a second quote, not a retry
        // of the first. 60.00 + 60.00 does not fit inside 100.00, and what it does not fit inside is
        // *available* funds: the first quote's hold is still outstanding against the same balance.
        quoteBySellAmount(6_000L, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("could not hold 6000 minor units of EUR")));

        assertThat(quoteRowCount()).as("a refused quote leaves no row to execute later").isEqualTo(1);
        assertThat(ledger.holds()).as("and no second hold").hasSize(1);
    }

    @Test
    void issuingAQuoteReallyRemovesTheSellAmountFromAvailableFunds() throws Exception {
        quoteBySellAmount(6_000L, null).andExpect(status().isCreated());

        assertThat(ledger.availableMinor(aliceEurLedgerAccount)).isEqualTo(ALICE_EUR_BALANCE - 6_000L);
    }

    @Test
    void aSecondQuoteThatFitsInsideWhatIsLeftIsStillIssued() throws Exception {
        quoteBySellAmount(6_000L, null).andExpect(status().isCreated());

        // The refusal above is about available funds, not about holding two quotes at once: 40.00 is
        // exactly what is left, and a user splitting an exchange in two must not be blocked.
        quoteBySellAmount(4_000L, null).andExpect(status().isCreated());

        assertThat(ledger.availableMinor(aliceEurLedgerAccount)).isZero();
        assertThat(quoteRowCount()).isEqualTo(2);
    }

    @Test
    void theHoldIsPlacedOnTheSellWalletForTheSellAmountAndExpiresWithTheQuote() throws Exception {
        UUID quoteId = quoteIdOf(quoteBySellAmount(6_000L, null).andExpect(status().isCreated()));
        FxQuote stored = quotes.findById(quoteId).orElseThrow();

        assertThat(ledger.holds()).singleElement().satisfies(hold -> {
            assertThat(hold.ledgerAccountId()).isEqualTo(aliceEurLedgerAccount);
            assertThat(hold.amountMinor()).isEqualTo(6_000L);
            assertThat(hold.currency()).isEqualTo("EUR");
            // Keyed on the quote, never on the client's Idempotency-Key: the ledger's key space is
            // global while a client's key is unique only to that client.
            assertThat(hold.idempotencyKey()).isEqualTo("fx-quote:" + quoteId);
            assertThat(hold.referenceId()).isEqualTo(quoteId);
            // One expiry, not two. A hold outliving its quote would be funds nobody will release; a
            // quote outliving its hold would be a firm rate with nothing behind it.
            assertThat(hold.expiresAt()).isEqualTo(stored.expiresAt());
        });
        assertThat(stored.reservationId()).as("the quote remembers which hold funds it").isNotNull();
    }

    @Test
    void theQuoteIsPersistedActiveWithBothRatesAtTheRateColumnsFullScale() throws Exception {
        UUID quoteId = quoteIdOf(quoteBySellAmount(10_000L, null).andExpect(status().isCreated()));

        FxQuote stored = quotes.findById(quoteId).orElseThrow();
        assertThat(stored.state()).isEqualTo(FxQuoteState.ACTIVE);
        assertThat(stored.sellCurrency()).isEqualTo("EUR");
        assertThat(stored.buyCurrency()).isEqualTo("GBP");
        assertThat(stored.sellMinor()).isEqualTo(10_000L);
        assertThat(stored.spreadBps()).isEqualTo(50);
        // 50 bps off a 0.86 mid is 0.8557, and 100.00 EUR at that rate is 85.57 GBP.
        assertThat(stored.clientRate()).isEqualTo("0.8557000000");
        assertThat(stored.buyMinor()).isEqualTo(8_557L);
        // Digit for digit at numeric(20,10)'s scale, both ways through the column. The phase's first
        // listed gotcha is silent truncation on write; rates cross this boundary as decimal text, so
        // Postgres performs the only scaling there is.
        assertThat(stored.midRate()).isEqualTo(EUR_GBP_MID);
        assertThat(stored.expiresAt()).isEqualTo(stored.createdAt().plus(Duration.ofSeconds(30)));
    }

    @Test
    void aRetryWithTheSameIdempotencyKeyReturnsTheSameQuoteAndHoldsNothingExtra() throws Exception {
        String key = UUID.randomUUID().toString();

        UUID first = quoteIdOf(quoteBySellAmount(6_000L, key).andExpect(status().isCreated()));
        UUID retried = quoteIdOf(quoteBySellAmount(6_000L, key).andExpect(status().isCreated()));

        // Rule 3 matters more here than on a payment: a retry that reserved a second time would turn a
        // redelivered request into a decline of a request that had already succeeded.
        assertThat(retried).isEqualTo(first);
        assertThat(quoteRowCount()).isEqualTo(1);
        assertThat(ledger.holds()).hasSize(1);
        assertThat(ledger.availableMinor(aliceEurLedgerAccount)).isEqualTo(ALICE_EUR_BALANCE - 6_000L);
    }

    @Test
    void aQuoteAskedForByBuyAmountReservesTheSellSideAndIsRefusedTheSameWay() throws Exception {
        // The Exchange screen lets either side be typed in; the hold is always on the sell currency,
        // whichever side the number came from.
        quoteByBuyAmount(6_000L).andExpect(status().isCreated());

        assertThat(ledger.holds()).singleElement().satisfies(hold -> {
            assertThat(hold.currency()).isEqualTo("EUR");
            // 60.00 GBP at the client rate 0.8557 costs 70.12 EUR, and it is that - not the 60.00 the
            // client typed - that has to be held.
            assertThat(hold.amountMinor()).isEqualTo(7_012L);
        });

        quoteByBuyAmount(6_000L).andExpect(status().isConflict());
        assertThat(quoteRowCount()).isEqualTo(1);
    }

    @Test
    void aQuoteWithoutAnIdempotencyKeyReservesNothingAtAll() throws Exception {
        mockMvc.perform(post("/api/fx/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(6_000L, null))
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isBadRequest());

        assertThat(ledger.holds()).isEmpty();
        assertThat(ledger.availableMinor(aliceEurLedgerAccount)).isEqualTo(ALICE_EUR_BALANCE);
        assertThat(quoteRowCount()).isZero();
    }

    @Test
    void aCallerWithNoWalletInTheSellCurrencyIsRefusedBeforeAnythingIsHeld() throws Exception {
        mockMvc.perform(post("/api/fx/quotes")
                        .header(FxQuoteController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(6_000L, null))
                        .with(jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isConflict());

        assertThat(ledger.holds()).isEmpty();
        assertThat(quoteRowCount()).isZero();
    }

    private ResultActions quoteBySellAmount(long sellMinor, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/fx/quotes")
                .header(FxQuoteController.IDEMPOTENCY_KEY_HEADER,
                        idempotencyKey == null ? UUID.randomUUID().toString() : idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(sellMinor, null))
                .with(jwt().jwt(builder -> builder.subject(alice.toString()))));
    }

    private ResultActions quoteByBuyAmount(long buyMinor) throws Exception {
        return mockMvc.perform(post("/api/fx/quotes")
                .header(FxQuoteController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(null, buyMinor))
                .with(jwt().jwt(builder -> builder.subject(alice.toString()))));
    }

    private static String body(Long sellMinor, Long buyMinor) {
        return """
                {"sellCurrency":"EUR","buyCurrency":"GBP","sellMinor":%s,"buyMinor":%s}"""
                .formatted(sellMinor, buyMinor);
    }

    private UUID quoteIdOf(ResultActions response) throws Exception {
        String json = response.andReturn().getResponse().getContentAsString();
        return UUID.fromString(jsonMapper.readTree(json).get("quoteId").asString());
    }

    private long quoteRowCount() {
        return jdbcClient.sql("select count(*) from fx_quote").query(Long.class).single();
    }

    /** Seeds the two projections quoting reads, exactly as the Kafka consumers would have. */
    private UUID seedWallet(UUID userId, String currency) {
        UUID accountId = UUID.randomUUID();
        UUID ledgerAccountId = UUID.randomUUID();

        AccountOwner owner = new AccountOwner(accountId);
        owner.apply(userId, currency, AccountOwner.WALLET, Instant.now());
        accountOwners.saveAndFlush(owner);

        LedgerAccountRef ref = new LedgerAccountRef(ledgerAccountId);
        ref.apply(accountId, Instant.now());
        ledgerAccountRefs.saveAndFlush(ref);

        return ledgerAccountId;
    }
}
