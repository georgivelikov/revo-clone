package com.revolutclone.notification.consumer;

import com.revolutclone.contracts.events.PaymentCompletedV1;
import com.revolutclone.contracts.events.PaymentFailedV1;
import com.revolutclone.contracts.events.PaymentInitiatedV1;
import com.revolutclone.messaging.idempotent.IdempotentHandlerFactory;
import com.revolutclone.messaging.spring.EventTypeRouter;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The saga's three payment states, all on one topic keyed by payment id - so each payment's
 * initiated/completed/failed events arrive in order on the same partition, which is what lets
 * {@link PaymentCompletedEventHandler} rely on {@link PaymentInitiatedEventHandler}'s projection
 * already existing.
 *
 * <p>Every handler is wrapped for idempotency: all three push, and two of them write. A duplicate
 * push is not merely wasteful - a second {@code TRANSFER_FAILED} row would show the user the same
 * failure twice and add two to their unread badge.
 */
@Component
public class PaymentEventKafkaListener {

    /** Keyed by paymentId - see plans/phase-00-foundations.md's topic catalogue. */
    private static final String TOPIC = "payment.event.v1";

    private final EventTypeRouter router;

    public PaymentEventKafkaListener(PaymentInitiatedEventHandler initiated, PaymentCompletedEventHandler completed,
            PaymentFailedEventHandler failed, IdempotentHandlerFactory factory, JsonMapper jsonMapper) {
        this.router = EventTypeRouter.builder(jsonMapper)
                .register(PaymentInitiatedV1.class, factory.wrap(initiated, NotificationConsumerGroup.NAME))
                .register(PaymentCompletedV1.class, factory.wrap(completed, NotificationConsumerGroup.NAME))
                .register(PaymentFailedV1.class, factory.wrap(failed, NotificationConsumerGroup.NAME))
                .build();
    }

    @KafkaListener(topics = TOPIC, groupId = NotificationConsumerGroup.NAME)
    public void onMessage(String rawJson) {
        router.onMessage(rawJson);
    }
}
