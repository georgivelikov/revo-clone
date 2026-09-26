package com.revolutclone.notification.consumer;

import com.revolutclone.contracts.events.NotificationPushV1;
import com.revolutclone.messaging.idempotent.IdempotentHandlerFactory;
import com.revolutclone.messaging.spring.KafkaEventHandlerAdapter;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The third topic the phase's design detail names. One payload type, so a plain
 * {@link KafkaEventHandlerAdapter} rather than a router - but still wrapped for idempotency, because
 * the handler both persists and pushes.
 */
@Component
public class NotificationPushKafkaListener {

    /** Keyed by userId - see plans/phase-00-foundations.md's topic catalogue. */
    private static final String TOPIC = "notification.push.v1";

    private final KafkaEventHandlerAdapter<NotificationPushV1> adapter;

    public NotificationPushKafkaListener(NotificationPushEventHandler delegate, IdempotentHandlerFactory factory,
            JsonMapper jsonMapper) {
        this.adapter = new KafkaEventHandlerAdapter<>(factory.wrap(delegate, NotificationConsumerGroup.NAME),
                jsonMapper, NotificationPushV1.class);
    }

    @KafkaListener(topics = TOPIC, groupId = NotificationConsumerGroup.NAME)
    public void onMessage(String rawJson) {
        adapter.onMessage(rawJson);
    }
}
