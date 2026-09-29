package com.revolutclone.analytics.consumer;

import com.revolutclone.analytics.readmodel.CardAuthorizationFact;
import com.revolutclone.analytics.readmodel.CardAuthorizationFactRepository;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AuthorizationSettledV1;
import com.revolutclone.messaging.api.EventHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * "The money actually moved" - the event the whole phase exists to make legible. Records
 * {@code settledMinor} (wallet currency) and the wire {@code state} ({@code SETTLED} or
 * {@code PARTIALLY_SETTLED}) verbatim; {@code excessMinor}/{@code releasedMinor} are not
 * projected here - task 06.1 records the fact of settlement, not the categorisation/aggregate
 * logic that later tasks build on top of it.
 */
@Component
public class AuthorizationSettledEventHandler implements EventHandler<AuthorizationSettledV1> {

    private final CardAuthorizationFactRepository facts;

    public AuthorizationSettledEventHandler(CardAuthorizationFactRepository facts) {
        this.facts = facts;
    }

    @Override
    @Transactional
    public void handle(EventEnvelope<AuthorizationSettledV1> envelope) {
        AuthorizationSettledV1 event = envelope.payload();
        CardAuthorizationFact fact = facts.findById(event.authorizationId())
                .orElseGet(() -> new CardAuthorizationFact(event.authorizationId()));
        fact.applySettled(event.cardId(), event.userId(), event.accountId(), event.panLast4(),
                event.merchantName(), event.merchantMcc(), event.merchantCountry(), event.state(),
                event.settledMinor(), envelope.occurredAt());
        facts.save(fact);
    }
}
