package com.revolutclone.card.consumer;

import com.revolutclone.card.authz.AuthorizationDecision;
import com.revolutclone.card.authz.AuthorizationService;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AuthorizationRequestedV1;
import com.revolutclone.messaging.api.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The business half of the authorization consumer: unwraps the envelope, runs the decision chain, and
 * says what was decided. Kafka binding lives in {@link CardAuthorizationKafkaListener}, so this is
 * testable with no broker at all - which is how all three of this task's tests drive it.
 */
@Component
public class AuthorizationRequestedEventHandler implements EventHandler<AuthorizationRequestedV1> {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationRequestedEventHandler.class);

    private final AuthorizationService authorizations;

    public AuthorizationRequestedEventHandler(AuthorizationService authorizations) {
        this.authorizations = authorizations;
    }

    @Override
    public void handle(EventEnvelope<AuthorizationRequestedV1> envelope) {
        AuthorizationRequestedV1 request = envelope.payload();
        AuthorizationDecision decision = authorizations.authorize(request);

        // Never the PAN - only the network's reference, which is what an operator would search by.
        log.info("authorization {} at {}: {}{}", request.networkRef(), request.merchantName(),
                decision.state(),
                decision.isApproved() ? " (reservation " + decision.reservationId() + ")"
                        : " (" + decision.declineReason() + ")");
    }
}
