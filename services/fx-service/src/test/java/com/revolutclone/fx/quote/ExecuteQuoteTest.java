package com.revolutclone.fx.quote;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * plans/phase-04-fx-multicurrency.md's <strong>Executing</strong>, from the endpoint's side:
 * "Quote must be {@code ACTIVE} and unexpired - check server-side, never trust the client's clock.
 * Emits {@code FxExecutionRequestedV1} via the outbox."
 *
 * <p>The ledger half - the capture and the five- or six-leg posting - is asserted in
 * ledger-service, where the entry actually exists ({@code FxEntryBalanceTest},
 * {@code ConcurrentQuoteExecuteTest}). What belongs here is everything that decides <em>whether</em>
 * an execution is published at all, and that exactly one is: the state transition, the server-clock
 * expiry check, the owner check, and the compensation that unwinds a quote the ledger refused.
 *
 * <p>Nothing is stubbed out for the outbox: the row really lands in {@code outbox_event} in the
 * same transaction as the state change, which is the property CLAUDE.md rule 6 is about. The
 * publisher's poll is turned off so the rows stay there to be counted.
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
        "revolut.fx.quote.ttl=30s"
})
class ExecuteQuoteTest {

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
    private UUID aliceGbpLedgerAccount;

    @BeforeEach
    void aliceHasOneHundredEurosAndTheRateIsKnown() {
        jdbcClient.sql("delete from fx_quote").update();
        jdbcClient.sql("delete from outbox_event").update();
        accountOwners.deleteAll();
        ledgerAccountRefs.deleteAll();
        ledger.reset();

        alice = UUID.randomUUID();
        aliceEurLedgerAccount = seedWallet(alice, "EUR");
        aliceGbpLedgerAccount = seedWallet(alice, "GBP");
        ledger.fund(aliceEurLedgerAccount, ALICE_EUR_BALANCE);

        rates.update(new FxRateCache.LatestRate("EUR/GBP", EUR_GBP_MID, Instant.now(), "test"));
    }

    @Test
    void executingAnActiveQuoteConsumesItAndEmitsOneExecutionThroughTheOutbox() throws Exception {
        UUID quoteId = aQuoteFor(6_000L);

        execute(quoteId, alice).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("CONSUMED"));

        assertThat(quotes.findById(quoteId).orElseThrow().state()).isEqualTo(FxQuoteState.CONSUMED);
        assertThat(executionsFor(quoteId)).isEqualTo(1L);
        // The ledger is not called: the capture is the ledger's own reaction to the event, which is
        // why killing this service right here still settles the exchange.
        assertThat(ledger.released()).isEmpty();
    }

    @Test
    void theExecutionCarriesTheLegsTheQuoteWasPricedAtAndTheWalletToCredit() throws Exception {
        UUID quoteId = aQuoteFor(10_000L);

        execute(quoteId, alice).andExpect(status().isAccepted());

        var payload = jsonMapper.readTree(payloadOf(quoteId)).get("payload");
        assertThat(payload.get("sellMinor").asLong()).isEqualTo(10_000L);
        // 100.00 EUR: the pool gives up 86.00 GBP at the mid, Alice is paid 85.57 at the client
        // rate, and the 0.43 difference is the spread.
        assertThat(payload.get("poolMidMinor").asLong()).isEqualTo(8_600L);
        assertThat(payload.get("clientBuyMinor").asLong()).isEqualTo(8_557L);
        assertThat(payload.get("feeMinor").asLong()).isEqualTo(43L);
        assertThat(payload.get("buyLedgerAccountId").asString())
                .isEqualTo(aliceGbpLedgerAccount.toString());
    }

    @Test
    void oneQuoteExecutedTwiceConcurrentlyPublishesExactlyOneExecution() throws Exception {
        UUID quoteId = aQuoteFor(6_000L);

        CyclicBarrier bothReady = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<?>[] both = new CompletableFuture<?>[2];
            for (int i = 0; i < both.length; i++) {
                both[i] = CompletableFuture.runAsync(() -> {
                    try {
                        bothReady.await(10, TimeUnit.SECONDS);
                        execution.execute(quoteId, alice);
                    } catch (RuntimeException ex) {
                        throw ex;
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                }, executor);
            }
            CompletableFuture.allOf(both).get(60, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // Neither caller fails - a user who double-taps confirm is not doing anything wrong - but
        // only one of them moved the row from ACTIVE, and only that one published. One event means
        // one journal entry downstream, without relying on the ledger's key to catch it.
        assertThat(executionsFor(quoteId)).isEqualTo(1L);
        assertThat(quotes.findById(quoteId).orElseThrow().state()).isEqualTo(FxQuoteState.CONSUMED);
    }

    @Test
    void reExecutingAConsumedQuoteReturnsTheSameOutcomeWithoutPublishingAgain() throws Exception {
        UUID quoteId = aQuoteFor(6_000L);
        execute(quoteId, alice).andExpect(status().isAccepted());

        // A quote is a one-shot resource, so the retry's outcome is the original's - this is how a
        // repeated Idempotency-Key is honoured without a second key space to keep.
        execute(quoteId, alice).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("CONSUMED"));
        assertThat(executionsFor(quoteId)).isEqualTo(1L);
    }

    @Test
    void anExpiredQuoteIsRefusedOnTheServerClockAndPublishesNothing() throws Exception {
        UUID quoteId = aQuoteFor(6_000L);
        expireInTheDatabase(quoteId);

        execute(quoteId, alice).andExpect(status().isConflict());

        assertThat(executionsFor(quoteId)).isZero();
        assertThat(quotes.findById(quoteId).orElseThrow().state()).isEqualTo(FxQuoteState.ACTIVE);
    }

    @Test
    void anotherUsersQuoteIsNotFoundRatherThanForbidden() throws Exception {
        UUID quoteId = aQuoteFor(6_000L);

        execute(quoteId, UUID.randomUUID()).andExpect(status().isNotFound());

        assertThat(executionsFor(quoteId)).isZero();
    }

    @Test
    void executingWithoutAnIdempotencyKeyPublishesNothing() throws Exception {
        UUID quoteId = aQuoteFor(6_000L);

        mockMvc.perform(post("/api/fx/quotes/{id}/execute", quoteId)
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isBadRequest());

        assertThat(executionsFor(quoteId)).isZero();
    }

    @Test
    void aLedgerRejectionCancelsTheQuoteReleasesTheHoldAndTellsTheUser() throws Exception {
        UUID quoteId = aQuoteFor(6_000L);
        execute(quoteId, alice).andExpect(status().isAccepted());
        UUID reservationId = quotes.findById(quoteId).orElseThrow().reservationId();

        execution.compensate(quoteId, "RESERVATION_NOT_HELD");

        assertThat(quotes.findById(quoteId).orElseThrow().state()).isEqualTo(FxQuoteState.CANCELLED);
        assertThat(ledger.released()).containsExactly(reservationId);
        assertThat(failuresFor(quoteId)).isEqualTo(1L);

        // Compensating twice must not release a second time or announce a second failure: the
        // transition is conditional on CONSUMED, and a replayed rejection finds it already gone.
        execution.compensate(quoteId, "RESERVATION_NOT_HELD");
        assertThat(ledger.released()).hasSize(1);
        assertThat(failuresFor(quoteId)).isEqualTo(1L);
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

    private ResultActions execute(UUID quoteId, UUID caller) throws Exception {
        return mockMvc.perform(post("/api/fx/quotes/{id}/execute", quoteId)
                .header(FxQuoteController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                .with(jwt().jwt(builder -> builder.subject(caller.toString()))));
    }

    /**
     * Moves the row's own {@code expires_at} into the past rather than waiting 30 seconds or
     * winding a clock: the server-clock check is the thing under test, so the server's clock is
     * exactly what must not be tampered with.
     */
    private void expireInTheDatabase(UUID quoteId) {
        jdbcClient.sql("update fx_quote set expires_at = now() - interval '1 second' where id = :id")
                .param("id", quoteId)
                .update();
    }

    private long executionsFor(UUID quoteId) {
        return outboxCount("FxExecutionRequestedV1", quoteId);
    }

    private long failuresFor(UUID quoteId) {
        return outboxCount("FxExecutionFailedV1", quoteId);
    }

    private long outboxCount(String type, UUID quoteId) {
        return jdbcClient.sql("""
                        select count(*) from outbox_event
                        where topic = 'fx.quote.v1' and type = :type and partition_key = :key
                        """)
                .param("type", type)
                .param("key", quoteId.toString())
                .query(Long.class)
                .single();
    }

    private String payloadOf(UUID quoteId) {
        List<String> payloads = jdbcClient.sql("""
                        select payload::text from outbox_event
                        where topic = 'fx.quote.v1' and type = 'FxExecutionRequestedV1' and partition_key = :key
                        """)
                .param("key", quoteId.toString())
                .query(String.class)
                .list();
        assertThat(payloads).hasSize(1);
        return payloads.getFirst();
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
