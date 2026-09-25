package com.revolutclone.payment.web;

import static com.revolutclone.payment.testsupport.AccountViewFixture.activeWallet;
import static com.revolutclone.payment.testsupport.TransferRequests.transferBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.contracts.events.PaymentInitiatedV1;
import com.revolutclone.messaging.outbox.OutboxEvent;
import com.revolutclone.messaging.outbox.OutboxEventRepository;
import com.revolutclone.payment.domain.Payment;
import com.revolutclone.payment.domain.PaymentRepository;
import com.revolutclone.payment.domain.PaymentStatus;
import com.revolutclone.payment.readmodel.AccountView;
import com.revolutclone.payment.readmodel.AccountViewRepository;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * plans/phase-03-payments-and-ui.md's Tests table: "POST → PENDING → COMPLETED; both balances
 * correct." Against a real Postgres, via the real HTTP stack, security filter chain, validation
 * and JPA mapping - nothing here is mocked except the JWT signature.
 *
 * <p><strong>Where the phase's end-to-end assertion actually lands, and why.</strong> The saga
 * is four steps across three services; task 03.3 is step 1 only. The two halves of the phase's
 * assertion are therefore split by where the code that can satisfy them lives:
 * <ul>
 *   <li><em>POST → PENDING</em>, and the instruction that determines both balances, are
 *       asserted here in full: the 202, the persisted PENDING row, and exactly one outbox
 *       {@code PaymentInitiatedV1} naming the debited account, the credited account, the amount
 *       and the currency. That event <em>is</em> "both balances correct" expressed as an
 *       instruction - payment-service holds no balances of its own and must not, since the
 *       ledger is the only component allowed to know them.</li>
 *   <li><em>→ COMPLETED</em> is asserted here at the aggregate level ({@link
 *       #theLedgerResultMovesAnAcceptedPaymentToCompleted}), because the consumer that will
 *       drive that transition from a real {@code EntryPostedV1} is task 03.4. The
 *       balance-level end of it belongs to ledger-service, whose own
 *       {@code DebitDecreasesCustomerBalanceTest} and {@code ZeroSumInvariantTest} already
 *       prove a posted transfer moves both balances by equal and opposite amounts.</li>
 * </ul>
 * The one thing deliberately not attempted is a single test process running payment-service and
 * ledger-service against a live broker: {@code testcontainers-kafka} does not start on Docker
 * Desktop for Windows (see docs/spring-boot-4-notes.md), so such a test would not run on this
 * machine at all.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        // The outbox publisher must not fire during the test: these assertions are about the
        // rows it would later pick up, and there is no broker here to publish them to.
        "revolut.messaging.outbox.poll-delay-ms=300000"
})
class TransferHappyPathTest {

    private static final String TOPIC = "payment.event.v1";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private AccountViewRepository accountViews;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private JsonMapper jsonMapper;

    private UUID alice;
    private UUID bob;
    private AccountView aliceEur;
    private AccountView bobEur;

    @BeforeEach
    void seedTwoPartiesWithEuroWallets() {
        outbox.deleteAll();
        payments.deleteAll();
        accountViews.deleteAll();

        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        aliceEur = activeWallet(accountViews, alice, "EUR");
        bobEur = activeWallet(accountViews, bob, "EUR");
    }

    @Test
    void aTransferIsAcceptedAsPendingWithTwoHundredAndTwoNotTwoHundredAndOne() throws Exception {
        String responseBody = mockMvc.perform(post("/api/payments")
                        .header(PaymentController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(aliceEur.getAccountId(), bobEur.getAccountId(), 1_000L, "EUR", "rent"))
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                // 202, not 201. The money has not moved; the instruction has been accepted.
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.paymentId").isNotEmpty())
                .andExpect(jsonPath("$.amountMinor").value(1_000L))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.reference").value("rent"))
                // Present and explicitly null, not dropped - the UI has to be able to tell
                // "PENDING, no reason yet" from a missing field.
                .andExpect(jsonPath("$.ledgerEntryId").value(nullValue()))
                .andExpect(jsonPath("$.failureReason").value(nullValue()))
                .andReturn().getResponse().getContentAsString();

        UUID paymentId = paymentIdOf(responseBody);
        Payment persisted = payments.findById(paymentId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(persisted.getInitiatorUserId()).isEqualTo(alice);
        assertThat(persisted.getSourceAccountId()).isEqualTo(aliceEur.getAccountId());
        assertThat(persisted.getTargetAccountId()).isEqualTo(bobEur.getAccountId());
        assertThat(persisted.getAmountMinor()).isEqualTo(1_000L);
    }

    @Test
    void acceptingATransferPublishesExactlyOneLedgerInstructionInTheSameTransaction() throws Exception {
        UUID paymentId = postTransfer(UUID.randomUUID().toString(), 2_500L);

        List<OutboxEvent> rows = outbox.findAll();
        assertThat(rows).hasSize(1);

        OutboxEvent row = rows.getFirst();
        assertThat(row.getTopic()).isEqualTo(TOPIC);
        // CLAUDE.md hard rule 5: keyed by the entity whose ordering matters. For this topic the
        // catalogue says paymentId, so every event about one payment stays on one partition and
        // COMPLETED can never be consumed before the INITIATED that caused it.
        assertThat(row.getPartitionKey()).isEqualTo(paymentId.toString());
        assertThat(row.getType()).isEqualTo("PaymentInitiatedV1");
        assertThat(row.getPublishedAt()).as("not published yet - the poller is disabled here").isNull();

        JsonNode envelope = jsonMapper.readTree(row.getPayload());
        // CLAUDE.md hard rule 4: every event carries these four.
        assertThat(UUID.fromString(envelope.get("eventId").asString())).isNotNull();
        assertThat(Instant.parse(envelope.get("occurredAt").asString())).isNotNull();
        assertThat(envelope.get("correlationId").asString()).isNotBlank();
        assertThat(envelope.get("version").asInt()).isEqualTo(1);

        // This payload is "both balances correct" stated as an instruction: debit exactly this
        // account, credit exactly that one, by exactly this many minor units of this currency.
        PaymentInitiatedV1 event = jsonMapper.treeToValue(envelope.get("payload"), PaymentInitiatedV1.class);
        assertThat(event.paymentId()).isEqualTo(paymentId);
        assertThat(event.senderAccountId()).isEqualTo(aliceEur.getAccountId());
        assertThat(event.receiverAccountId()).isEqualTo(bobEur.getAccountId());
        assertThat(event.amountMinor()).isEqualTo(2_500L);
        assertThat(event.currency()).isEqualTo("EUR");
    }

    @Test
    void theClientsIdempotencyKeyIsNeverWhatTheLedgerWillSee() throws Exception {
        String clientKey = "client-chosen-key-" + UUID.randomUUID();
        UUID paymentId = postTransfer(clientKey, 700L);

        Payment payment = payments.findById(paymentId).orElseThrow();
        assertThat(payment.getIdempotencyKey()).isEqualTo(clientKey);

        // journal_entry.idempotency_key is GLOBALLY unique while this key is unique only per
        // initiator, so forwarding it would let two users who picked the same UUID collide in
        // the ledger - and one would silently receive the other's journal entry. The ledger key
        // is derived from the payment id instead, and ledger-service independently requires the
        // "payment:" prefix for a TRANSFER entry.
        assertThat(payment.ledgerIdempotencyKey()).isEqualTo("payment:" + paymentId);
        assertThat(payment.ledgerIdempotencyKey()).isNotEqualTo(clientKey);

        // The strongest form of the rule: the client's key does not appear anywhere in what
        // leaves this service, so no downstream consumer can even choose to use it.
        List<OutboxEvent> rows = outbox.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getPayload()).doesNotContain(clientKey);
    }

    @Test
    void theSameKeyTwiceYieldsOnePaymentOneInstructionAndTheSameAnswer() throws Exception {
        String key = UUID.randomUUID().toString();

        String first = postForBody(key, 1_000L);
        String second = postForBody(key, 1_000L);

        assertThat(paymentIdOf(first)).isEqualTo(paymentIdOf(second));
        assertThat(payments.findAll()).hasSize(1);
        // The duplicate must not produce a second instruction to move money - that, not the
        // response shape, is what makes a double-click safe.
        assertThat(outbox.findAll()).hasSize(1);
    }

    @Test
    void theLedgerResultMovesAnAcceptedPaymentToCompleted() throws Exception {
        UUID paymentId = postTransfer(UUID.randomUUID().toString(), 1_000L);
        UUID ledgerEntryId = UUID.randomUUID();

        // Step 3 of the saga, driven by hand: the consumer that will do this from a real
        // EntryPostedV1 is task 03.4. What is being pinned here is that a payment accepted by
        // the endpoint above is in a state the success leg can actually be applied to, and that
        // the result is durable - not the consumer's own wiring.
        Payment accepted = payments.findById(paymentId).orElseThrow();
        accepted.markCompleted(ledgerEntryId);
        payments.saveAndFlush(accepted);

        Payment settled = payments.findById(paymentId).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(settled.getLedgerEntryId()).isEqualTo(ledgerEntryId);
        assertThat(settled.getFailureReason()).isNull();
        assertThat(settled.getUpdatedAt()).isAfterOrEqualTo(settled.getCreatedAt());
    }

    private UUID postTransfer(String idempotencyKey, long amountMinor) throws Exception {
        return paymentIdOf(postForBody(idempotencyKey, amountMinor));
    }

    private String postForBody(String idempotencyKey, long amountMinor) throws Exception {
        return mockMvc.perform(post("/api/payments")
                        .header(PaymentController.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(aliceEur.getAccountId(), bobEur.getAccountId(),
                                amountMinor, "EUR", "rent"))
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
    }

    private UUID paymentIdOf(String responseBody) {
        return UUID.fromString(jsonMapper.readTree(responseBody).get("paymentId").asString());
    }
}
