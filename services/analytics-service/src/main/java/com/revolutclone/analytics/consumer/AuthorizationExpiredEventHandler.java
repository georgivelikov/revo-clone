package com.revolutclone.analytics.consumer;

import com.revolutclone.analytics.readmodel.CardAuthorizationFact;
import com.revolutclone.analytics.readmodel.CardAuthorizationFactRepository;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AuthorizationExpiredV1;
import com.revolutclone.messaging.api.EventHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seven days passed with no settlement - the same holder-visible outcome as a reversal (the
 * hold is gone, no money ever moved), recorded under its own {@link CardAuthorizationFact#EXPIRED}
 * state so a later task can still tell the two apart if it ever needs to.
 */
@Component
public class AuthorizationExpiredEventHandler implements EventHandler<AuthorizationExpiredV1> {

    private final CardAuthorizationFactRepository facts;

    public AuthorizationExpiredEventHandler(CardAuthorizationFactRepository facts) {
        this.facts = facts;
    }

    @Override
    @Transactional
    public void handle(EventEnvelope<AuthorizationExpiredV1> envelope) {
        AuthorizationExpiredV1 event = envelope.payload();
        CardAuthorizationFact fact = facts.findById(event.authorizationId())
                .orElseGet(() -> new CardAuthorizationFact(event.authorizationId()));
        fact.applyExpired(event.cardId(), event.userId(), event.accountId(), event.panLast4(),
                event.merchantName(), envelope.occurredAt());
        facts.save(fact);
    }
}
