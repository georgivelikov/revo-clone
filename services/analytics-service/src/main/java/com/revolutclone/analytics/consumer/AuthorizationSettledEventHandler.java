package com.revolutclone.analytics.consumer;

import com.revolutclone.analytics.readmodel.CardAuthorizationFact;
import com.revolutclone.analytics.readmodel.CardAuthorizationFactRepository;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AuthorizationSettledV1;
import com.revolutclone.messaging.api.EventHandler;
import com.revolutclone.messaging.idempotent.IdempotentHandlerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * "The money actually moved" - the event the whole phase exists to make legible. Records
 * {@code settledMinor} (wallet currency) and the wire {@code state} ({@code SETTLED} or
 * {@code PARTIALLY_SETTLED}) verbatim; {@code excessMinor}/{@code releasedMinor} are not
 * projected here - task 06.1 records the fact of settlement, not the categorisation/aggregate
 * logic that later tasks build on top of it.
 *
 * <p>Task 06.3 adds the second half of the same event: after the {@code CardAuthorizationFact}
 * upsert above, this also drives {@code monthly_spend}'s incremental aggregate via
 * {@link MonthlySpendProjectionHandler}. The two halves are deliberately guarded differently - see
 * that class's own javadoc for why the fact upsert stays as it was (naturally redelivery-safe) and
 * only the aggregate increment is wrapped in an {@link com.revolutclone.messaging.idempotent.IdempotentHandler}.
 * Both still run inside this method's one transaction, so a crash between them cannot record a
 * settlement fact without also recording its spend, or vice versa.
 */
@Component
public class AuthorizationSettledEventHandler implements EventHandler<AuthorizationSettledV1> {

    /**
     * The {@code processed_event} dedup key's own namespace for this increment - distinct from
     * {@code CardAuthorizationKafkaListener}'s {@code @KafkaListener} consumer group, since this
     * guard is about one side effect (the aggregate), not about consuming the topic at all.
     */
    private static final String MONTHLY_SPEND_CONSUMER_GROUP = "analytics-service-monthly-spend";

    private final CardAuthorizationFactRepository facts;
    private final EventHandler<AuthorizationSettledV1> monthlySpend;

    public AuthorizationSettledEventHandler(CardAuthorizationFactRepository facts,
            MonthlySpendProjectionHandler monthlySpendProjection, IdempotentHandlerFactory idempotentHandlerFactory) {
        this.facts = facts;
        this.monthlySpend = idempotentHandlerFactory.wrap(monthlySpendProjection, MONTHLY_SPEND_CONSUMER_GROUP);
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

        monthlySpend.handle(envelope);
    }
}
