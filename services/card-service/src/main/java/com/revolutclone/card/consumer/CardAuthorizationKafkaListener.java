package com.revolutclone.card.consumer;

import com.revolutclone.contracts.events.AuthorizationRequestedV1;
import com.revolutclone.messaging.spring.EventTypeRouter;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The thin {@code @KafkaListener} the authorization chain hangs off.
 *
 * <p><strong>An {@link EventTypeRouter} even though only one type is handled.</strong>
 * {@code card.authorization.v1} is this domain's only topic: it carries {@code CardIssuedV1} and both
 * of card-service's own answers as well as the network's request, and every one of those comes back to
 * this consumer. A router ignores what it has not registered; a bare adapter assuming one payload type
 * per topic would try to read a {@code CardIssuedV1} as an authorization request.
 *
 * <p><strong>Deliberately does not implement {@code ConsumerSeekAware}</strong>, unlike this service's
 * two projection listeners. An authorization is a side effect, not a projection - seeking to the
 * beginning on every assignment would re-decide, and re-hold funds for, every authorization the card
 * network has ever sent.
 *
 * <p><strong>And deliberately not wrapped in {@code IdempotentHandler}.</strong> The card flow's
 * idempotency anchor is {@code networkRef}, not {@code eventId}: the network resends under a fresh
 * {@code eventId}, which an {@code eventId} dedupe cannot see, while the unique index on
 * {@code card_authorization.network_ref} catches both cases. See {@code AuthorizationService}'s javadoc
 * and {@code DuplicateNetworkRefTest}.
 */
@Component
public class CardAuthorizationKafkaListener {

    /** Keyed by cardId, so two taps on one card are evaluated in order - CLAUDE.md rule 5. */
    private static final String TOPIC = "card.authorization.v1";

    private static final String CONSUMER_GROUP = "card-service";

    private final EventTypeRouter router;

    public CardAuthorizationKafkaListener(AuthorizationRequestedEventHandler authorizationRequested,
            JsonMapper jsonMapper) {
        this.router = EventTypeRouter.builder(jsonMapper)
                .register(AuthorizationRequestedV1.class, authorizationRequested)
                .build();
    }

    @KafkaListener(topics = TOPIC, groupId = CONSUMER_GROUP)
    public void onMessage(String rawJson) {
        router.onMessage(rawJson);
    }
}
