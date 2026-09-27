package com.revolutclone.fx.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * plans/phase-04-fx-multicurrency.md's task 6, and the phase's own worked example: "take another
 * and let it expire without touching it - confirm disables and the held funds reappear as
 * available." The mechanics live in {@link QuoteExpirySweep}; what belongs here is the observable
 * behaviour - a swept quote reads {@code EXPIRED}, its hold's funds are back, and executing it
 * afterwards is refused rather than silently accepted.
 *
 * <p>Driven by {@link QuoteExpirySweep#sweepDueAsOf} with an explicit instant, not the
 * {@code @Scheduled} method, for the reason {@code ReservationService}'s own sweep test does the
 * same: one cycle, deterministically, rather than a real-time wait or a manipulated system clock.
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
        "revolut.fx.ledger.base-url=http://ledger.invalid",
        "revolut.fx.quote.spread-bps=50",
        "revolut.fx.quote.ttl=30s",
        // The background @Scheduled tick is stretched out so every sweep in this test is the
        // explicit sweepDueAsOf call below, not a race against it - the same reason
        // ledger-service's own reservation-sweep tests stretch out its sweep-delay-ms.
        "revolut.fx.quote.sweep-delay-ms=300000"
})
class QuoteExpiryTest {

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
    private QuoteExpirySweep sweep;
    @Autowired
    private QuoteExecutionService execution;
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
        jdbcClient.sql("delete from outbox_event").update();
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
    void aDueQuoteIsMarkedExpiredAndItsHoldReleased() throws Exception {
        UUID quoteId = aQuoteFor(6_000L);
        assertThat(ledger.availableMinor(aliceEurLedgerAccount)).isEqualTo(ALICE_EUR_BALANCE - 6_000L);
        expireInTheDatabase(quoteId);

        int swept = sweep.sweepDueAsOf(Instant.now());

        assertThat(swept).isEqualTo(1);
        assertThat(quotes.findById(quoteId).orElseThrow().state()).isEqualTo(FxQuoteState.EXPIRED);
        UUID reservationId = quotes.findById(quoteId).orElseThrow().reservationId();
        assertThat(ledger.released()).containsExactly(reservationId);
        assertThat(ledger.availableMinor(aliceEurLedgerAccount)).isEqualTo(ALICE_EUR_BALANCE);
    }

    @Test
    void executingAQuoteTheSweepAlreadyExpiredIsRefusedWithConflict() throws Exception {
        UUID quoteId = aQuoteFor(6_000L);
        expireInTheDatabase(quoteId);
        sweep.sweepDueAsOf(Instant.now());

        execute(quoteId).andExpect(status().isConflict());

        assertThat(quotes.findById(quoteId).orElseThrow().state()).isEqualTo(FxQuoteState.EXPIRED);
    }

    @Test
    void aQuoteNotYetDueIsLeftAloneByTheSweep() throws Exception {
        UUID quoteId = aQuoteFor(6_000L);

        int swept = sweep.sweepDueAsOf(Instant.now());

        assertThat(swept).isZero();
        assertThat(quotes.findById(quoteId).orElseThrow().state()).isEqualTo(FxQuoteState.ACTIVE);
        assertThat(ledger.released()).isEmpty();
    }

    @Test
    void aQuoteAlreadyExecutedIsNotTouchedByTheSweepEvenPastItsExpiry() throws Exception {
        UUID quoteId = aQuoteFor(6_000L);
        execute(quoteId).andExpect(status().isAccepted());
        expireInTheDatabase(quoteId);

        int swept = sweep.sweepDueAsOf(Instant.now());

        assertThat(swept).isZero();
        assertThat(quotes.findById(quoteId).orElseThrow().state()).isEqualTo(FxQuoteState.CONSUMED);
        // The reservation was captured, not released - sweeping a CONSUMED quote would be exactly
        // the "reservation expiry racing execution" bug the ledger's own capture re-check guards
        // against from the other side.
        assertThat(ledger.released()).isEmpty();
    }

    private UUID aQuoteFor(long sellMinor) throws Exception {
        ResultActions response = mockMvc.perform(post("/api/fx/quotes")
                        .header(FxQuoteController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sellCurrency":"EUR","buyCurrency":"GBP","sellMinor":%d}""".formatted(sellMinor))
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isCreated());
        String json = response.andReturn().getResponse().getContentAsString();
        return UUID.fromString(jsonMapper.readTree(json).get("quoteId").asString());
    }

    private ResultActions execute(UUID quoteId) throws Exception {
        return mockMvc.perform(post("/api/fx/quotes/{id}/execute", quoteId)
                .header(FxQuoteController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                .with(jwt().jwt(builder -> builder.subject(alice.toString()))));
    }

    /**
     * Moves the row's own {@code expires_at} into the past rather than waiting 30 seconds - the
     * sweep's own due-or-not decision is what is under test, so the quote's actual expiry column
     * is exactly what must be manipulated, not the clock the sweep reads.
     */
    private void expireInTheDatabase(UUID quoteId) {
        jdbcClient.sql("update fx_quote set expires_at = now() - interval '1 second' where id = :id")
                .param("id", quoteId)
                .update();
    }

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
