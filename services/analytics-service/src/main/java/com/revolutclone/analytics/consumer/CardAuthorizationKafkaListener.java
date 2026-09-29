package com.revolutclone.analytics.consumer;

import com.revolutclone.contracts.events.AuthorizationApprovedV1;
import com.revolutclone.contracts.events.AuthorizationExpiredV1;
import com.revolutclone.contracts.events.AuthorizationReversedV1;
import com.revolutclone.contracts.events.AuthorizationSettledV1;
import com.revolutclone.messaging.spring.EventTypeRouter;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code card.authorization.v1} carries every stage of the authorization flow, so this routes
 * on the envelope's {@code type} and picks out only the four that represent a hold this
 * service should track: {@link AuthorizationApprovedV1}, {@link AuthorizationSettledV1},
 * {@link AuthorizationReversedV1}, {@link AuthorizationExpiredV1}.
 *
 * <p><strong>{@code AuthorizationDeclinedV1} is deliberately not registered.</strong> A decline
 * means no hold was ever placed and no money was ever at risk - there is nothing for a spend
 * projection to record, and a {@code CARD_NOT_FOUND} decline does not even carry an
 * authorization id to key a row on. {@code CardIssuedV1} (no spend information),
 * {@code AuthorizationRequestedV1} (the raw network request - carries a full PAN and has no
 * userId to attribute it to) and {@code SettlementRequestedV1}/{@code ReversalRequestedV1}
 * (simulator-service's requests, not card-service's outcomes) are equally out of scope: every
 * one of them is a request or a non-event for analytics, not a fact worth recording.
 *
 * <p><strong>Not {@code ConsumerSeekAware}</strong>, for the same reason as
 * {@code LedgerEntryKafkaListener}: this consumer's own table is a running transaction
 * history, not a small reference mapping, so ordinary committed-offset consumption is the right
 * steady state rather than a full replay on every restart.
 */
@Component
public class CardAuthorizationKafkaListener {

    /** Keyed by cardId - CLAUDE.md rule 5, so one card's whole lifecycle stays in order. */
    private static final String TOPIC = "card.authorization.v1";

    private static final String CONSUMER_GROUP = "analytics-service";

    private final EventTypeRouter router;

    public CardAuthorizationKafkaListener(AuthorizationApprovedEventHandler approved,
            AuthorizationSettledEventHandler settled, AuthorizationReversedEventHandler reversed,
            AuthorizationExpiredEventHandler expired, JsonMapper jsonMapper) {
        this.router = EventTypeRouter.builder(jsonMapper)
                .register(AuthorizationApprovedV1.class, approved)
                .register(AuthorizationSettledV1.class, settled)
                .register(AuthorizationReversedV1.class, reversed)
                .register(AuthorizationExpiredV1.class, expired)
                .build();
    }

    @KafkaListener(topics = TOPIC, groupId = CONSUMER_GROUP)
    public void onMessage(String rawJson) {
        router.onMessage(rawJson);
    }
}
