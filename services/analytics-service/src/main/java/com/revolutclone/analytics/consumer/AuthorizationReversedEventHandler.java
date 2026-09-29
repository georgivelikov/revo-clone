package com.revolutclone.analytics.consumer;

import com.revolutclone.analytics.readmodel.CardAuthorizationFact;
import com.revolutclone.analytics.readmodel.CardAuthorizationFactRepository;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AuthorizationReversedV1;
import com.revolutclone.messaging.api.EventHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The hold is gone and no money ever moved - "an authorization alone triggers nothing; a
 * reversal never rounds up" (plans/phase-06-vaults-analytics.md's Tests table,
 * {@code RoundUpOnSettlementOnlyTest}, task 06.7). Recording the state transition now is what
 * lets that later task tell a reversed authorization apart from a settled one without
 * re-deriving it.
 */
@Component
public class AuthorizationReversedEventHandler implements EventHandler<AuthorizationReversedV1> {

    private final CardAuthorizationFactRepository facts;

    public AuthorizationReversedEventHandler(CardAuthorizationFactRepository facts) {
        this.facts = facts;
    }

    @Override
    @Transactional
    public void handle(EventEnvelope<AuthorizationReversedV1> envelope) {
        AuthorizationReversedV1 event = envelope.payload();
        CardAuthorizationFact fact = facts.findById(event.authorizationId())
                .orElseGet(() -> new CardAuthorizationFact(event.authorizationId()));
        fact.applyReversed(event.cardId(), event.userId(), event.accountId(), event.panLast4(),
                event.merchantName(), envelope.occurredAt());
        facts.save(fact);
    }
}
