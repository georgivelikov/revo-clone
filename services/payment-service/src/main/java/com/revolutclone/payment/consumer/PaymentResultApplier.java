package com.revolutclone.payment.consumer;

import com.revolutclone.contracts.events.PaymentCompletedV1;
import com.revolutclone.contracts.events.PaymentFailedV1;
import com.revolutclone.messaging.api.OutboxAppender;
import com.revolutclone.payment.domain.Payment;
import com.revolutclone.payment.domain.PaymentRepository;
import com.revolutclone.payment.domain.PaymentStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step 3 of plans/phase-03-payments-and-ui.md's transfer saga: <em>payment-svc consumes the
 * result → PENDING → COMPLETED, or PENDING → FAILED(reason) → outbox PaymentCompletedV1 /
 * PaymentFailedV1.</em> The state change and the event announcing it share one transaction, for
 * the same reason acceptance does (CLAUDE.md hard rule 6): a payment that is COMPLETED in this
 * database but never told anyone is how a user ends up staring at a spinner over money that has
 * already moved.
 *
 * <p>Both event types that drive this land here rather than in the two {@code EventHandler}s
 * themselves, because the interesting behaviour - what a second result for an already-settled
 * payment does - is identical on both legs and is the whole subject of
 * {@code LedgerEventRedeliveryTest}.
 *
 * <h2>Why this must tolerate a repeat that {@code processed_event} cannot catch</h2>
 *
 * <p>{@code ledger.entry.v1} carries <strong>one {@code EntryPostedV1} per posting</strong>, so a
 * two-legged transfer produces <em>two</em> success events for one payment - different
 * {@code eventId}s, the same {@code entryId} and {@code referenceId}. They are not redeliveries,
 * so the {@code processed_event} layer correctly lets both through, and the second one must not
 * append a second {@code PaymentCompletedV1}. The guard is therefore state-based: a payment
 * already in the state the event would move it to is a no-op. Combined with the
 * {@code IdempotentHandler} above it, that covers both shapes of repeat - Kafka redelivering one
 * event, and the ledger legitimately emitting two.
 *
 * <p>What it deliberately does <em>not</em> do is soften a genuine contradiction. A success event
 * for a payment already FAILED (or vice versa) means the ledger and this service disagree about
 * money, and {@link Payment}'s state machine throws on that illegal transition rather than
 * quietly overwriting - the phase spec is explicit about this, and a payment that silently
 * flipped states is precisely the bug double-entry bookkeeping exists to make visible. Such a
 * message retries and then dead-letters (see {@code KafkaErrorHandlingConfig}), which is the
 * correct outcome for a message no amount of retrying will make consistent: it stops, loudly,
 * with the evidence preserved.
 *
 * <h2>{@code payment.end.to.end} (plans/phase-08-load-and-observability.md task 3)</h2>
 *
 * <p>"Started on accept, stopped on terminal state" cannot be a live {@code Timer.Sample} held
 * across the Kafka round-trip between the two - the start happened in a different method call,
 * possibly a different process entirely by the time the result arrives. {@link Payment#getCreatedAt()}
 * is that start time already, durably, so the duration is just {@code now - createdAt}, computed
 * here and recorded directly. Only on the transition that actually settles the payment - the
 * idempotent-replay branches above return before reaching it, so a redelivered result event
 * never records a second, misleadingly-short duration for the same payment.
 */
@Component
public class PaymentResultApplier {

    private static final Logger log = LoggerFactory.getLogger(PaymentResultApplier.class);

    /** Keyed by paymentId, 30-day retention - see plans/phase-00-foundations.md's catalogue. */
    private static final String OUTBOX_TOPIC = "payment.event.v1";

    private final PaymentRepository payments;
    private final OutboxAppender outbox;
    private final Clock clock;
    private final Timer endToEndTimer;

    public PaymentResultApplier(PaymentRepository payments, OutboxAppender outbox, Clock clock,
            MeterRegistry meterRegistry) {
        this.payments = payments;
        this.outbox = outbox;
        this.clock = clock;
        this.endToEndTimer = Timer.builder("payment.end.to.end")
                .description("Time from a payment's acceptance to its terminal state (COMPLETED or FAILED)")
                .register(meterRegistry);
    }

    @Transactional
    public void complete(UUID paymentId, UUID ledgerEntryId) {
        Optional<Payment> found = payments.findById(paymentId);
        if (found.isEmpty()) {
            // A TRANSFER entry naming a payment this service has never heard of. Not retryable
            // and not this service's problem to fix at runtime; ack it and leave the evidence.
            log.warn("ledger entry {} references unknown payment {} - ignoring", ledgerEntryId, paymentId);
            return;
        }

        Payment payment = found.get();
        if (payment.getStatus() == PaymentStatus.COMPLETED) {
            if (!ledgerEntryId.equals(payment.getLedgerEntryId())) {
                // Two different journal entries claiming the same payment. The derived
                // payment:<paymentId> ledger key makes this impossible unless something upstream
                // is broken, and it is worth a loud line rather than a silent second no-op.
                log.error("payment {} is already COMPLETED by ledger entry {} but entry {} also claims it",
                        paymentId, payment.getLedgerEntryId(), ledgerEntryId);
            }
            return;
        }

        payment.markCompleted(ledgerEntryId);
        payments.save(payment);
        outbox.append(OUTBOX_TOPIC, paymentId.toString(), new PaymentCompletedV1(paymentId));
        recordEndToEnd(payment);
        log.info("payment {} COMPLETED by ledger entry {}", paymentId, ledgerEntryId);
    }

    @Transactional
    public void fail(UUID paymentId, String reason) {
        Optional<Payment> found = payments.findById(paymentId);
        if (found.isEmpty()) {
            log.warn("ledger rejection ({}) references unknown payment {} - ignoring", reason, paymentId);
            return;
        }

        Payment payment = found.get();
        if (payment.getStatus() == PaymentStatus.FAILED) {
            return;
        }

        payment.markFailed(reason);
        payments.save(payment);
        outbox.append(OUTBOX_TOPIC, paymentId.toString(), new PaymentFailedV1(paymentId, reason));
        recordEndToEnd(payment);
        log.info("payment {} FAILED: {}", paymentId, reason);
    }

    private void recordEndToEnd(Payment payment) {
        endToEndTimer.record(Duration.between(payment.getCreatedAt(), clock.instant()));
    }
}
