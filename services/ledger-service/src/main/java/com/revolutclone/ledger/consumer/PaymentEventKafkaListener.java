package com.revolutclone.ledger.consumer;

import com.revolutclone.contracts.events.PaymentInitiatedV1;
import com.revolutclone.messaging.idempotent.IdempotentHandlerFactory;
import com.revolutclone.messaging.spring.EventTypeRouter;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The thin {@code @KafkaListener} for the transfer saga's inbound leg. {@code payment.event.v1}
 * is a mixed topic - payment-service publishes {@code PaymentCompletedV1} and
 * {@code PaymentFailedV1} onto it as well, and both are this service's <em>own</em> results
 * coming back - so the listener routes on the envelope's {@code type} through
 * {@link EventTypeRouter} and ignores everything it did not register. Consuming its own echo
 * would be a posting loop.
 *
 * <p>{@code groupId=ledger-service} is this consumer's identity in {@code processed_event}'s
 * {@code consumer_group} column, shared with {@link AccountOpenedKafkaListener} - one group per
 * service, not per topic. Wrapping the handler in the {@code IdempotentHandler} is what makes a
 * redelivered {@code PaymentInitiatedV1} do no work at all; the derived
 * {@code payment:<paymentId>} ledger key is the second line of defence for the case
 * {@code processed_event} cannot see - a <em>different</em> {@code eventId} carrying the same
 * payment, which is what a republished outbox row looks like.
 *
 * <p>Unlike {@link AccountOpenedKafkaListener} this listener does <strong>not</strong> seek to
 * the beginning on assignment: {@code payment.event.v1} is a 30-day-retention event log, not a
 * compacted projection source, and replaying it would re-present every transfer ever made. The
 * committed offset is the correct resume point here, and durability across a ledger outage comes
 * from the log itself - stop this service mid-transfer and the payment stays PENDING until it
 * starts again and consumes the message, which is the phase's own Verification step 4.
 */
@Component
public class PaymentEventKafkaListener {

    /** Keyed by paymentId, 30-day retention - see plans/phase-00-foundations.md's catalogue. */
    private static final String TOPIC = "payment.event.v1";

    private static final String CONSUMER_GROUP = "ledger-service";

    private final EventTypeRouter router;

    public PaymentEventKafkaListener(PaymentInitiatedEventHandler paymentInitiated,
            IdempotentHandlerFactory factory, JsonMapper jsonMapper) {
        this.router = EventTypeRouter.builder(jsonMapper)
                .register(PaymentInitiatedV1.class, factory.wrap(paymentInitiated, CONSUMER_GROUP))
                .build();
    }

    @KafkaListener(topics = TOPIC, groupId = CONSUMER_GROUP)
    public void onMessage(String rawJson) {
        router.onMessage(rawJson);
    }
}
