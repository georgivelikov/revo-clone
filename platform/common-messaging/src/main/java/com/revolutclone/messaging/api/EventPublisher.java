package com.revolutclone.messaging.api;

/**
 * The one seam through which anything in this project reaches a broker. Business code never
 * implements or calls this directly - only {@code OutboxPublisher} does, and only the
 * {@code spring/} adapter implements it. The payload is already-serialized JSON (an
 * {@code EventEnvelope} encoded by the outbox appender) so this port stays free of any Kafka
 * type: no {@code ProducerRecord}, no headers, no {@code KafkaTemplate}.
 */
public interface EventPublisher {

    /**
     * @param topic         the destination topic
     * @param partitionKey  the entity id that orders this message - see CLAUDE.md hard rule 5
     * @param payloadJson   the already-serialized {@code EventEnvelope<? extends DomainEvent>}
     */
    void publish(String topic, String partitionKey, String payloadJson);
}
