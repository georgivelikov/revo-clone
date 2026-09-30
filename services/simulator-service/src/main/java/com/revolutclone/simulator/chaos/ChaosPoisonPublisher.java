package com.revolutclone.simulator.chaos;

import com.revolutclone.messaging.api.EventPublisher;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * plans/phase-07-resilience-and-scale.md's {@code poison <topic>} lever: "Publish an unparseable
 * message - observe DLT, not a stall." Every consumer in this project takes the raw envelope as a
 * JSON string ({@code KafkaEventHandlerAdapter#onMessage(String)}), so a value that fails
 * {@code JsonMapper.readTree} is enough to reproduce a genuine poison-pill message - no need to
 * publish raw non-UTF-8 bytes, which {@code ErrorHandlingDeserializer}'s {@code StringDeserializer}
 * delegate would decode without complaint anyway. The failure this lever is meant to prove
 * survives happens one layer up, in {@code DefaultErrorClassifier} classifying the resulting
 * {@code JacksonException} as {@code FATAL}.
 */
@Component
public class ChaosPoisonPublisher {

    private static final String POISON_PAYLOAD = "{ this is not valid JSON, on purpose -- chaos.poison";

    private final EventPublisher eventPublisher;

    public ChaosPoisonPublisher(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void poison(String topic) {
        eventPublisher.publish(topic, "chaos-poison-" + UUID.randomUUID(), POISON_PAYLOAD);
    }
}
