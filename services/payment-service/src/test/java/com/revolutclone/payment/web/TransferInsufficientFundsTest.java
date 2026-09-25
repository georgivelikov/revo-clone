package com.revolutclone.payment.web;

import static com.revolutclone.payment.testsupport.AccountViewFixture.activeWallet;
import static com.revolutclone.payment.testsupport.TransferRequests.transferBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.messaging.outbox.OutboxEventRepository;
import com.revolutclone.payment.domain.IllegalPaymentTransitionException;
import com.revolutclone.payment.domain.Payment;
import com.revolutclone.payment.domain.PaymentRepository;
import com.revolutclone.payment.domain.PaymentStatus;
import com.revolutclone.payment.readmodel.AccountView;
import com.revolutclone.payment.readmodel.AccountViewRepository;
import com.revolutclone.payment.testsupport.NoOpJwtDecoderTestConfig;
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
 * plans/phase-03-payments-and-ui.md's Tests table: "Payment ends FAILED with the reason;
 * <strong>sender's balance unchanged</strong>."
 *
 * <p>The italicised half is the interesting one, and the reason it holds is structural rather
 * than defensive: <strong>payment-service never touches a balance</strong>. It cannot leave one
 * half-moved because it has no way to move one. Its entire contribution to a transfer is a
 * single {@code PaymentInitiatedV1} instruction, and ledger-service either posts that entry
 * whole or posts none of it, in one local transaction - which is precisely why
 * plans/phase-03-payments-and-ui.md says there is no compensation step in the same-currency
 * case. So the assertions that carry the "balance unchanged" guarantee here are:
 * <ul>
 *   <li>an overdrawing transfer is <em>accepted</em>, not pre-rejected - this service holds no
 *       balances and must not guess at them, and a wrong guess would be far worse than a late
 *       rejection;</li>
 *   <li>the rejection leg emits no further money-moving instruction, so there is nothing to
 *       compensate and nothing that could debit the sender twice;</li>
 *   <li>a request refused synchronously leaves no payment row and, critically, no outbox row -
 *       an outbox row is an order to move money that nothing downstream second-guesses.</li>
 * </ul>
 * That the ledger genuinely leaves the sender's balance untouched when it rejects an entry is
 * ledger-service's own {@code InsufficientFundsTest}, which asserts it against real balance
 * rows. It is not restated here because payment-service owns no balance to assert about.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000",
        // A deliberately low cap so the "above the cap" case is reachable with a readable
        // number. It is not a balance - see PaymentLimitsProperties.
        "revolut.payments.max-amount-minor=500000"
})
class TransferInsufficientFundsTest {

    private static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";

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
    private AccountView aliceEur;
    private AccountView bobEur;

    @BeforeEach
    void seedTwoPartiesWithEuroWallets() {
        outbox.deleteAll();
        payments.deleteAll();
        accountViews.deleteAll();

        alice = UUID.randomUUID();
        aliceEur = activeWallet(accountViews, alice, "EUR");
        bobEur = activeWallet(accountViews, UUID.randomUUID(), "EUR");
    }

    @Test
    void aTransferTheSenderCannotAffordIsStillAcceptedBecauseOnlyTheLedgerKnowsBalances() throws Exception {
        UUID paymentId = postTransfer(400_000L);

        // 202, not a synchronous rejection. payment-service consulting a balance would mean
        // either reading another service's database or caching a number that is stale the
        // instant it is read - both worse than letting the authoritative component decide.
        assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(outbox.findAll()).hasSize(1);
    }

    @Test
    void theLedgersRejectionEndsThePaymentFailedWithTheReasonAndNoFurtherInstruction() throws Exception {
        UUID paymentId = postTransfer(400_000L);

        // Step 3 of the saga on its failure leg, driven by hand: the consumer that will do this
        // from a real EntryRejectedV1 is task 03.4.
        Payment accepted = payments.findById(paymentId).orElseThrow();
        accepted.markFailed(INSUFFICIENT_FUNDS);
        payments.saveAndFlush(accepted);

        Payment failed = payments.findById(paymentId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo(INSUFFICIENT_FUNDS);
        assertThat(failed.getLedgerEntryId()).as("a rejected entry was never posted").isNull();

        // Still exactly the one instruction from the accept. No compensating entry is emitted
        // and none is needed: the ledger posted nothing, so there is nothing to undo - and no
        // second instruction that could debit the sender after the first already failed.
        assertThat(outbox.findAll()).hasSize(1);
    }

    @Test
    void aFailedPaymentIsTerminalSoALateOrRedeliveredSuccessCannotResurrectIt() throws Exception {
        UUID paymentId = postTransfer(400_000L);
        Payment payment = payments.findById(paymentId).orElseThrow();
        payment.markFailed(INSUFFICIENT_FUNDS);
        payments.saveAndFlush(payment);

        Payment terminal = payments.findById(paymentId).orElseThrow();

        // The state machine throws rather than silently updating, so a contradictory ledger
        // result is loud. Silently accepting it is how a payment ends up disagreeing with the
        // ledger about whether money moved.
        assertThatThrownBy(() -> terminal.markCompleted(UUID.randomUUID()))
                .isInstanceOf(IllegalPaymentTransitionException.class);
        assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void anAmountAboveThePerTransactionCapIsRefusedWithoutWritingAnything() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .header(PaymentController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(aliceEur.getAccountId(), bobEur.getAccountId(),
                                500_001L, "EUR", "too big"))
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(startsWith("AMOUNT_ABOVE_LIMIT")));

        // The whole point of validating inside the transaction, before the insert: a refusal
        // leaves no payment and - far more importantly - no outbox row.
        assertThat(payments.findAll()).isEmpty();
        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    void aNonPositiveAmountIsRefusedByValidationBeforeItReachesTheDomain() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .header(PaymentController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(aliceEur.getAccountId(), bobEur.getAccountId(),
                                0L, "EUR", "nothing"))
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isBadRequest());

        assertThat(payments.findAll()).isEmpty();
        assertThat(outbox.findAll()).isEmpty();
    }

    private UUID postTransfer(long amountMinor) throws Exception {
        String body = mockMvc.perform(post("/api/payments")
                        .header(PaymentController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(aliceEur.getAccountId(), bobEur.getAccountId(),
                                amountMinor, "EUR", "more than alice has"))
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(jsonMapper.readTree(body).get("paymentId").asString());
    }
}
