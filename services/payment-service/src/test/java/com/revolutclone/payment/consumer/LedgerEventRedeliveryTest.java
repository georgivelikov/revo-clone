package com.revolutclone.payment.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.contracts.DomainEvent;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.EntryPostedV1;
import com.revolutclone.contracts.events.EntryRejectedV1;
import com.revolutclone.messaging.api.EventHandler;
import com.revolutclone.messaging.idempotent.IdempotentHandlerFactory;
import com.revolutclone.messaging.outbox.OutboxEvent;
import com.revolutclone.messaging.outbox.OutboxEventRepository;
import com.revolutclone.payment.domain.Payment;
import com.revolutclone.payment.domain.PaymentRepository;
import com.revolutclone.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * plans/phase-03-payments-and-ui.md's Tests table: "{@code EntryPostedV1} twice → payment
 * completed once, no double-count."
 *
 * <p>"Twice" has two genuinely different shapes on this topic and both are covered here, because
 * they are stopped by different mechanisms and passing one proves nothing about the other:
 * <ul>
 *   <li><strong>The same event redelivered</strong> - identical {@code eventId}, which is what
 *       Kafka's at-least-once delivery produces after a rebalance or a failed offset commit.
 *       Stopped by {@code processed_event} ({@link IdempotentHandlerFactory}), which never calls
 *       the handler a second time.</li>
 *   <li><strong>Two distinct events for one entry</strong> - different {@code eventId}s, same
 *       {@code entryId}: ledger-service emits one {@code EntryPostedV1} <em>per posting</em>, so
 *       every two-legged transfer produces two success events. {@code processed_event} correctly
 *       lets both through; only {@link PaymentResultApplier}'s state guard stops the second from
 *       appending a second {@code PaymentCompletedV1}.</li>
 * </ul>
 * "No double-count" is asserted as exactly one outbox row per payment, since that row is what the
 * UI and notification-service react to - a second one would settle the same transfer twice on
 * screen and, in phase 06, in the analytics projection.
 *
 * <p>Drives the handlers directly through the same {@link IdempotentHandlerFactory} wrapping
 * {@link LedgerEntryKafkaListener} uses, rather than through a broker: Testcontainers' Kafka
 * module does not start on this machine (docs/spring-boot-4-notes.md), and the listener
 * contributes only the topic binding and the type routing on top of what is exercised here.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        // The outbox rows are the assertion; the publisher must not carry them off mid-test, and
        // there is no broker here to publish them to anyway.
        "revolut.messaging.outbox.poll-delay-ms=300000"
})
class LedgerEventRedeliveryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void oidcIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://127.0.0.1:8180/realms/revolut");
    }

    @Autowired
    private EntryPostedEventHandler postedDelegate;
    @Autowired
    private EntryRejectedEventHandler rejectedDelegate;
    @Autowired
    private IdempotentHandlerFactory idempotency;
    @Autowired
    private PaymentRepository payments;
    @Autowired
    private OutboxEventRepository outbox;

    private UUID sourceAccount;
    private UUID targetAccount;
    /**
     * The ledger names its own accounts on these events, not account-service's; payment-service
     * never resolves them, which is exactly why they are unrelated ids here.
     */
    private UUID senderLedgerAccount;
    private UUID receiverLedgerAccount;

    @BeforeEach
    void freshState() {
        outbox.deleteAll();
        payments.deleteAll();
        sourceAccount = UUID.randomUUID();
        targetAccount = UUID.randomUUID();
        senderLedgerAccount = UUID.randomUUID();
        receiverLedgerAccount = UUID.randomUUID();
    }

    @Test
    void theSameEntryPostedDeliveredTwiceCompletesThePaymentOnce() {
        UUID paymentId = pendingPayment();
        UUID entryId = UUID.randomUUID();
        EventEnvelope<EntryPostedV1> event = envelope(debitLeg(entryId, paymentId));

        posted().handle(event);
        posted().handle(event);

        Payment settled = payments.findById(paymentId).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(settled.getLedgerEntryId()).isEqualTo(entryId);
        assertThat(settled.getFailureReason()).isNull();
        assertThat(outboxTypes()).containsExactly("PaymentCompletedV1");
    }

    /**
     * The case {@code processed_event} cannot catch, and the reason
     * {@link PaymentResultApplier}'s state guard exists: one journal entry, two postings, two
     * events with different ids. Both are legitimate and neither is a redelivery.
     */
    @Test
    void bothPostingsOfOneTransferCompleteThePaymentOnce() {
        UUID paymentId = pendingPayment();
        UUID entryId = UUID.randomUUID();

        posted().handle(envelope(debitLeg(entryId, paymentId)));
        posted().handle(envelope(creditLeg(entryId, paymentId)));

        assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(outboxTypes())
                .as("one settled payment must announce itself exactly once, whatever the ledger's posting count")
                .containsExactly("PaymentCompletedV1");
    }

    @Test
    void theSameRejectionDeliveredTwiceFailsThePaymentOnceWithTheLedgersReason() {
        UUID paymentId = pendingPayment();
        EventEnvelope<EntryRejectedV1> event = envelope(new EntryRejectedV1(
                "payment:" + paymentId, senderLedgerAccount, "INSUFFICIENT_FUNDS", paymentId.toString()));

        rejected().handle(event);
        rejected().handle(event);

        Payment failed = payments.findById(paymentId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(failed.getLedgerEntryId()).as("nothing was posted, so there is no entry to point at").isNull();
        assertThat(outboxTypes()).containsExactly("PaymentFailedV1");
    }

    /**
     * Two distinct rejection events for the same payment - the shape a republished outbox row on
     * the ledger's side produces. Same guard, failure leg.
     */
    @Test
    void twoDistinctRejectionsForOnePaymentFailItOnce() {
        UUID paymentId = pendingPayment();

        rejected().handle(envelope(new EntryRejectedV1(
                "payment:" + paymentId, sourceAccount, "INSUFFICIENT_FUNDS", paymentId.toString())));
        rejected().handle(envelope(new EntryRejectedV1(
                "payment:" + paymentId, sourceAccount, "INSUFFICIENT_FUNDS", paymentId.toString())));

        assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(outboxTypes()).containsExactly("PaymentFailedV1");
    }

    /**
     * {@code ledger.entry.v1} is a shared topic: funding entries, fee sweeps, FX and card
     * settlements all land on it. A payment must not be settled by somebody else's entry.
     */
    @Test
    void anEntryThatIsNotATransferLeavesThePaymentPending() {
        UUID paymentId = pendingPayment();

        posted().handle(envelope(new EntryPostedV1(UUID.randomUUID(), senderLedgerAccount, "CREDIT", 50_000L,
                "EUR", 50_000L, "ADJUSTMENT", paymentId.toString())));

        assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    void aRejectionBelongingToAnFxQuoteIsNotAPaymentFailure() {
        UUID paymentId = pendingPayment();

        rejected().handle(envelope(new EntryRejectedV1("fxquote:" + UUID.randomUUID(), senderLedgerAccount,
                "INSUFFICIENT_FUNDS", paymentId.toString())));

        assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    void aTransferEntryForAnUnknownPaymentIsAckedAndIgnored() {
        UUID neverSeen = UUID.randomUUID();

        posted().handle(envelope(debitLeg(UUID.randomUUID(), neverSeen)));

        assertThat(payments.findAll()).isEmpty();
        assertThat(outbox.findAll()).isEmpty();
    }

    private EventHandler<EntryPostedV1> posted() {
        return idempotency.wrap(postedDelegate, "payment-service");
    }

    private EventHandler<EntryRejectedV1> rejected() {
        return idempotency.wrap(rejectedDelegate, "payment-service");
    }

    private UUID pendingPayment() {
        Payment payment = new Payment(UUID.randomUUID(), UUID.randomUUID().toString(), UUID.randomUUID(),
                sourceAccount, targetAccount, 1_000L, "EUR", "rent");
        payments.saveAndFlush(payment);
        return payment.getId();
    }

    private EntryPostedV1 debitLeg(UUID entryId, UUID paymentId) {
        // Signed per direction, exactly as PostingTransactionExecutor emits it.
        return new EntryPostedV1(entryId, senderLedgerAccount, "DEBIT", -1_000L, "EUR", 49_000L, "TRANSFER",
                paymentId.toString());
    }

    private EntryPostedV1 creditLeg(UUID entryId, UUID paymentId) {
        return new EntryPostedV1(entryId, receiverLedgerAccount, "CREDIT", 1_000L, "EUR", 51_000L, "TRANSFER",
                paymentId.toString());
    }

    private List<String> outboxTypes() {
        return outbox.findAll().stream().map(OutboxEvent::getType).toList();
    }

    private static <T extends DomainEvent> EventEnvelope<T> envelope(T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), Instant.now(), "corr-" + UUID.randomUUID(),
                payload.getClass().getSimpleName(), 1, payload);
    }
}
