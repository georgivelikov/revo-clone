package com.revolutclone.messaging.spring;

import com.revolutclone.contracts.DomainEvent;
import com.revolutclone.messaging.api.EventHandler;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dispatches one topic's messages to a different {@link EventHandler} per event type, keyed on
 * {@link com.revolutclone.contracts.EventEnvelope#type()} - which is exactly why that field is
 * on the envelope: "so a consumer can dispatch before deserializing the payload itself".
 *
 * <p>{@link KafkaEventHandlerAdapter} assumes one payload type per listener, which holds for a
 * single-purpose topic like {@code account.account.v1} but not for the two topics the transfer
 * saga's consumers read. {@code payment.event.v1} carries {@code PaymentInitiatedV1} alongside
 * {@code PaymentCompletedV1}/{@code PaymentFailedV1}, and {@code ledger.entry.v1} carries
 * {@code EntryPostedV1}/{@code EntryRejectedV1} alongside every reservation event - so both
 * ledger-service and payment-service have to pick the types they care about out of a mixed
 * stream.
 *
 * <p><strong>An unregistered type is ignored, not an error.</strong> A consumer reading a topic
 * it shares with other event types must tolerate them, and it must keep tolerating types that
 * do not exist yet: adding a new event to a topic is a backwards-compatible change, and a
 * router that threw would turn every such addition into a retry-then-dead-letter storm in every
 * unrelated consumer of that topic.
 *
 * <p>Wrap each handler in an {@code IdempotentHandler} <em>before</em> registering it, not the
 * router afterwards: the dedupe is per event type's own handler, and the router is
 * type-heterogeneous so there is no single {@code EventHandler<T>} to wrap.
 */
public final class EventTypeRouter {

    private final JsonMapper jsonMapper;
    private final Map<String, KafkaEventHandlerAdapter<?>> adaptersByType;

    private EventTypeRouter(JsonMapper jsonMapper, Map<String, KafkaEventHandlerAdapter<?>> adaptersByType) {
        this.jsonMapper = jsonMapper;
        this.adaptersByType = Map.copyOf(adaptersByType);
    }

    public static Builder builder(JsonMapper jsonMapper) {
        return new Builder(jsonMapper);
    }

    public void onMessage(String rawJson) {
        JsonNode node = jsonMapper.readTree(rawJson);
        JsonNode type = node.get("type");
        if (type == null) {
            throw new IllegalArgumentException("message is not an EventEnvelope - no \"type\" field: " + rawJson);
        }
        KafkaEventHandlerAdapter<?> adapter = adaptersByType.get(type.asString());
        if (adapter == null) {
            return;
        }
        adapter.onMessage(node);
    }

    /** The event types this router dispatches; everything else on the topic is ignored. */
    public Set<String> routedTypes() {
        return adaptersByType.keySet();
    }

    public static final class Builder {

        private final JsonMapper jsonMapper;
        private final Map<String, KafkaEventHandlerAdapter<?>> adaptersByType = new LinkedHashMap<>();

        private Builder(JsonMapper jsonMapper) {
            this.jsonMapper = jsonMapper;
        }

        /**
         * The routing key is {@code payloadType.getSimpleName()}, which is what
         * {@code OutboxAppenderImpl} writes into the envelope's {@code type} - derived from the
         * class on both sides rather than spelled out as a string literal anywhere.
         */
        public <T extends DomainEvent> Builder register(Class<T> payloadType, EventHandler<T> handler) {
            String type = payloadType.getSimpleName();
            if (adaptersByType.containsKey(type)) {
                throw new IllegalArgumentException("two handlers registered for event type " + type);
            }
            adaptersByType.put(type, new KafkaEventHandlerAdapter<>(handler, jsonMapper, payloadType));
            return this;
        }

        public EventTypeRouter build() {
            if (adaptersByType.isEmpty()) {
                throw new IllegalStateException("a router with no registered types would drop every message");
            }
            return new EventTypeRouter(jsonMapper, new HashMap<>(adaptersByType));
        }
    }
}
