package com.revolutclone.messaging.api;

/**
 * The one seam through which anything in this project reaches a broker. Only the {@code spring/}
 * adapter ({@code KafkaEventPublisher}) implements it. The payload is already-serialized JSON
 * (normally an {@code EventEnvelope} encoded by the outbox appender) so this port stays free of
 * any Kafka type: no {@code ProducerRecord}, no headers, no {@code KafkaTemplate}.
 *
 * <p>Business code that owns a database calls this only indirectly, through
 * {@code OutboxAppender} plus {@code OutboxPublisher} - never directly - so a Kafka publish and
 * the database write it depends on stay atomic. {@code simulator-service} is the one deliberate
 * exception: it owns no database (see docs/architecture.md), so there is no write for a direct
 * publish to be atomic with, and it calls this port straight from its scheduled producer -
 * see {@code SimulatorServiceApplication}.
 */
public interface EventPublisher {

    /**
     * @param topic         the destination topic
     * @param partitionKey  the entity id that orders this message - see CLAUDE.md hard rule 5
     * @param payloadJson   the already-serialized {@code EventEnvelope<? extends DomainEvent>}
     */
    void publish(String topic, String partitionKey, String payloadJson);
}
