package com.revolutclone.messaging.spring;

import com.revolutclone.contracts.DomainEvent;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.messaging.api.EventHandler;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The thin, generic body of the {@code @KafkaListener} the spec asks for. Each service writes
 * its own one-line {@code @KafkaListener} method (topic/groupId are per-service config, not
 * known here) and delegates straight into {@link #onMessage}, which does the actual
 * deserialize-then-dispatch to an {@link EventHandler}. That split is what makes every handler
 * unit-testable with no broker at all - only this class and {@link KafkaEventPublisher} import
 * Kafka types.
 */
public class KafkaEventHandlerAdapter<T extends DomainEvent> {

    private final EventHandler<T> handler;
    private final JsonMapper jsonMapper;
    private final Class<T> payloadType;

    public KafkaEventHandlerAdapter(EventHandler<T> handler, JsonMapper jsonMapper, Class<T> payloadType) {
        this.handler = handler;
        this.jsonMapper = jsonMapper;
        this.payloadType = payloadType;
    }

    public void onMessage(String rawJson) {
        JsonNode node = jsonMapper.readTree(rawJson);
        EventEnvelope<T> envelope = new EventEnvelope<>(
                UUID.fromString(node.get("eventId").asString()),
                Instant.parse(node.get("occurredAt").asString()),
                node.get("correlationId").asString(),
                node.get("type").asString(),
                node.get("version").asInt(),
                jsonMapper.treeToValue(node.get("payload"), payloadType));
        handler.handle(envelope);
    }
}
