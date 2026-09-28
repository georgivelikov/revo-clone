package com.revolutclone.simulator.card;

import com.revolutclone.contracts.DomainEvent;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AuthorizationRequestedV1;
import com.revolutclone.contracts.events.ReversalRequestedV1;
import com.revolutclone.contracts.events.SettlementRequestedV1;
import com.revolutclone.messaging.api.EventPublisher;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The card network's three outbound message types, all on {@code card.authorization.v1} and all
 * keyed by {@code cardId} - CLAUDE.md hard rule 5, and see {@link PendingSpend}'s javadoc for why
 * the key has to come from the caller rather than the event body for two of the three. Same shape
 * as {@code FxRateTickPublisher}: this service reuses {@code EventPublisher} directly rather than
 * going through an outbox, because it owns no database and there is no write for a publish to be
 * atomic with (see {@code SimulatorServiceApplication}).
 */
@Component
class CardNetworkEventPublisher {

    private static final String TOPIC = "card.authorization.v1";

    private final EventPublisher eventPublisher;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    CardNetworkEventPublisher(EventPublisher eventPublisher, JsonMapper jsonMapper, Clock clock) {
        this.eventPublisher = eventPublisher;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    void authorizationRequested(UUID cardId, AuthorizationRequestedV1 event) {
        publish(cardId, "AuthorizationRequestedV1", event);
    }

    void settlementRequested(UUID cardId, SettlementRequestedV1 event) {
        publish(cardId, "SettlementRequestedV1", event);
    }

    void reversalRequested(UUID cardId, ReversalRequestedV1 event) {
        publish(cardId, "ReversalRequestedV1", event);
    }

    private void publish(UUID cardId, String type, DomainEvent event) {
        EventEnvelope<DomainEvent> envelope = new EventEnvelope<>(
                UUID.randomUUID(), clock.instant(), UUID.randomUUID().toString(), type, 1, event);
        String json = jsonMapper.writeValueAsString(envelope);
        eventPublisher.publish(TOPIC, cardId.toString(), json);
    }
}
