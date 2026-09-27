package com.revolutclone.payment.web;

import static com.revolutclone.payment.testsupport.AccountViewFixture.activeWallet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.contracts.DomainEvent;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.EntryPostedV1;
import com.revolutclone.contracts.events.EntryRejectedV1;
import com.revolutclone.contracts.events.FxExecutionRequestedV1;
import com.revolutclone.messaging.api.EventHandler;
import com.revolutclone.messaging.idempotent.IdempotentHandlerFactory;
import com.revolutclone.messaging.outbox.OutboxEvent;
import com.revolutclone.messaging.outbox.OutboxEventRepository;
import com.revolutclone.payment.consumer.EntryPostedEventHandler;
import com.revolutclone.payment.consumer.EntryRejectedEventHandler;
import com.revolutclone.payment.domain.Payment;
import com.revolutclone.payment.domain.PaymentRepository;
import com.revolutclone.payment.domain.PaymentStatus;
import com.revolutclone.payment.fx.FxQuoteHold;
import com.revolutclone.payment.readmodel.AccountView;
import com.revolutclone.payment.readmodel.AccountViewRepository;
import com.revolutclone.payment.testsupport.FakeFxQuotes;
import com.revolutclone.payment.testsupport.FakeFxQuotesTestConfig;
import com.revolutclone.payment.testsupport.NoOpJwtDecoderTestConfig;
import java.time.Instant;
import java.util.List;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * plans/phase-04-fx-multicurrency.md task 8: "obtain a quote, then the same saga with
 * compensation on rejection." Alice (EUR) pays Bob (GBP): this pins the request → PENDING half
 * and both ledger-result halves, all without a real fx-service or ledger-service - the fake
 * stands in for the one sideways HTTP call, and the two consumer handlers are driven directly
 * with a hand-built ledger result, exactly as {@code LedgerEventRedeliveryTest} already does for
 * the same-currency case ({@code testcontainers-kafka} cannot start on this machine; see
 * docs/spring-boot-4-notes.md).
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import({NoOpJwtDecoderTestConfig.class, FakeFxQuotesTestConfig.class})
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000",
        "revolut.payment.fx.base-url=http://fx.invalid"
})
class CrossCurrencyTransferTest {

    private static final String FX_TOPIC = "fx.quote.v1";
    private static final String PAYMENT_TOPIC = "payment.event.v1";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FakeFxQuotes fxQuotes;
    @Autowired
    private PaymentRepository payments;
    @Autowired
    private AccountViewRepository accountViews;
    @Autowired
    private OutboxEventRepository outbox;
    @Autowired
    private EntryPostedEventHandler postedDelegate;
    @Autowired
    private EntryRejectedEventHandler rejectedDelegate;
    @Autowired
    private IdempotentHandlerFactory idempotency;
    @Autowired
    private JsonMapper jsonMapper;

    private UUID alice;
    private UUID bob;
    private AccountView aliceEur;
    private AccountView bobGbp;
    private UUID bobGbpLedgerAccount;

    @BeforeEach
    void aliceHasEurosAndBobHasAGbpWallet() {
        outbox.deleteAll();
        payments.deleteAll();
        accountViews.deleteAll();
        fxQuotes.reset();

        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        aliceEur = activeWallet(accountViews, alice, "EUR");
        bobGbp = activeWallet(accountViews, bob, "GBP");
        bobGbpLedgerAccount = UUID.randomUUID();
    }

    @Test
    void aCrossCurrencyTransferIsAcceptedAndPublishesFxExecutionRequestedNotPaymentInitiated() throws Exception {
        FxQuoteHold quote = aQuoteFor(10_000L, 8_557L);
        fxQuotes.respondWith(quote);

        String body = mockMvc.perform(post("/api/payments")
                        .header(PaymentController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(10_000L))
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.amountMinor").value(10_000L))
                .andExpect(jsonPath("$.targetCurrency").value("GBP"))
                .andExpect(jsonPath("$.targetAmountMinor").value(8_557L))
                .andReturn().getResponse().getContentAsString();

        UUID paymentId = UUID.fromString(jsonMapper.readTree(body).get("paymentId").asString());
        Payment payment = payments.findById(paymentId).orElseThrow();
        assertThat(payment.isCrossCurrency()).isTrue();
        assertThat(payment.getQuoteId()).isEqualTo(quote.quoteId());
        assertThat(payment.getTargetCurrency()).isEqualTo("GBP");
        assertThat(payment.getTargetAmountMinor()).isEqualTo(8_557L);

        // The fake was asked for exactly the transfer's own amount and Bob's user id, not
        // Alice's - the buy leg belongs to the recipient.
        assertThat(fxQuotes.calls()).singleElement().satisfies(command -> {
            assertThat(command.sellCurrency()).isEqualTo("EUR");
            assertThat(command.buyCurrency()).isEqualTo("GBP");
            assertThat(command.sellMinor()).isEqualTo(10_000L);
            assertThat(command.recipientUserId()).isEqualTo(bob);
        });

        List<OutboxEvent> rows = outbox.findAll();
        assertThat(rows).hasSize(1);
        OutboxEvent row = rows.getFirst();
        assertThat(row.getTopic()).isEqualTo(FX_TOPIC);
        // CLAUDE.md hard rule 5: keyed by the quote/reservation, not the payment - the same key
        // fx-service's own execute path would use for this exact event.
        assertThat(row.getPartitionKey()).isEqualTo(quote.quoteId().toString());
        assertThat(row.getType()).isEqualTo("FxExecutionRequestedV1");

        FxExecutionRequestedV1 event = jsonMapper.treeToValue(
                jsonMapper.readTree(row.getPayload()).get("payload"), FxExecutionRequestedV1.class);
        assertThat(event.quoteId()).isEqualTo(quote.quoteId());
        assertThat(event.reservationId()).isEqualTo(quote.reservationId());
        assertThat(event.buyLedgerAccountId()).isEqualTo(bobGbpLedgerAccount);
        assertThat(event.sellCurrency()).isEqualTo("EUR");
        assertThat(event.sellMinor()).isEqualTo(10_000L);
        assertThat(event.buyCurrency()).isEqualTo("GBP");
        assertThat(event.clientBuyMinor()).isEqualTo(8_557L);

        // Not the same-currency shape at all - no TRANSFER instruction was ever published.
        assertThat(outbox.findAll()).noneMatch(r -> r.getTopic().equals(PAYMENT_TOPIC));
    }

    @Test
    void theLedgerPostingTheFxEntryCompletesTheCrossCurrencyPayment() throws Exception {
        UUID paymentId = aCrossCurrencyPayment(6_000L, 5_143L);
        Payment payment = payments.findById(paymentId).orElseThrow();
        UUID entryId = UUID.randomUUID();

        posted().handle(envelope(new EntryPostedV1(entryId, bobGbpLedgerAccount, "CREDIT", 5_143L, "GBP",
                5_143L, "FX", payment.getQuoteId().toString())));

        Payment settled = payments.findById(paymentId).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(settled.getLedgerEntryId()).isEqualTo(entryId);
        assertThat(outboxTypes()).contains("PaymentCompletedV1");
    }

    @Test
    void theLedgerRejectingTheFxCaptureFailsTheCrossCurrencyPayment() throws Exception {
        UUID paymentId = aCrossCurrencyPayment(6_000L, 5_143L);
        Payment payment = payments.findById(paymentId).orElseThrow();

        // fx-service's own EntryRejectedEventHandler reacts to this identical event to cancel its
        // quote and release the hold - that is its compensation, not this service's. This
        // service's only job on this leg is the one it already has for a same-currency rejection:
        // tell the user.
        rejected().handle(envelope(new EntryRejectedV1("fxquote:" + payment.getQuoteId(), bobGbpLedgerAccount,
                "RESERVATION_NOT_HELD", payment.getQuoteId().toString())));

        Payment failed = payments.findById(paymentId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo("RESERVATION_NOT_HELD");
        assertThat(outboxTypes()).contains("PaymentFailedV1");
    }

    private UUID aCrossCurrencyPayment(long sellMinor, long buyMinor) throws Exception {
        fxQuotes.respondWith(aQuoteFor(sellMinor, buyMinor));
        String body = mockMvc.perform(post("/api/payments")
                        .header(PaymentController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(sellMinor))
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(jsonMapper.readTree(body).get("paymentId").asString());
    }

    private FxQuoteHold aQuoteFor(long sellMinor, long buyMinor) {
        long fee = buyMinor / 200; // an arbitrary, plausible 50bps-shaped split; not re-derived anywhere
        return new FxQuoteHold(UUID.randomUUID(), UUID.randomUUID(), bobGbpLedgerAccount, "EUR", sellMinor,
                "GBP", buyMinor + fee, buyMinor, fee, 0L, Instant.now().plusSeconds(30));
    }

    private String transferBody(long amountMinor) {
        return """
                {"sourceAccountId":"%s","targetAccountId":"%s","amountMinor":%d,\
                "currency":"EUR","reference":"lunch"}"""
                .formatted(aliceEur.getAccountId(), bobGbp.getAccountId(), amountMinor);
    }

    private EventHandler<EntryPostedV1> posted() {
        return idempotency.wrap(postedDelegate, "payment-service");
    }

    private EventHandler<EntryRejectedV1> rejected() {
        return idempotency.wrap(rejectedDelegate, "payment-service");
    }

    private List<String> outboxTypes() {
        return outbox.findAll().stream().map(OutboxEvent::getType).toList();
    }

    private static <T extends DomainEvent> EventEnvelope<T> envelope(T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), Instant.now(), UUID.randomUUID().toString(),
                payload.getClass().getSimpleName(), 1, payload);
    }
}
