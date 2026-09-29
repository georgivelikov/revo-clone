package com.revolutclone.analytics.consumer;

import com.revolutclone.analytics.categorisation.TransactionCategorisationService;
import com.revolutclone.analytics.readmodel.CardAuthorizationFact;
import com.revolutclone.analytics.readmodel.CardAuthorizationFactRepository;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AuthorizationApprovedV1;
import com.revolutclone.messaging.api.EventHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The row's usual origin: a hold was placed, so there is now a pending spend to record. See
 * {@link CardAuthorizationFact}'s javadoc for why later lifecycle events
 * ({@link AuthorizationSettledEventHandler}, {@link AuthorizationReversedEventHandler},
 * {@link AuthorizationExpiredEventHandler}) normally find this row already here rather than
 * having to create it themselves.
 *
 * <p>Not wrapped in an {@code IdempotentHandler} - same reasoning as
 * {@link EntryPostedEventHandler}: an upsert keyed on the authorization id, with no side effect
 * a redelivery could repeat.
 *
 * <p>Also this authorization's one and only shot at categorisation (task 06.2): this is the
 * first (and, on the normal path, the only) event that carries {@code merchantName}/
 * {@code merchantMcc} for it, so {@link TransactionCategorisationService#categoriseIfAbsent} is
 * called here rather than from the settlement/reversal/expiry handlers, none of which would have
 * anything new to categorise with.
 */
@Component
public class AuthorizationApprovedEventHandler implements EventHandler<AuthorizationApprovedV1> {

    private final CardAuthorizationFactRepository facts;
    private final TransactionCategorisationService categorisation;

    public AuthorizationApprovedEventHandler(CardAuthorizationFactRepository facts,
            TransactionCategorisationService categorisation) {
        this.facts = facts;
        this.categorisation = categorisation;
    }

    @Override
    @Transactional
    public void handle(EventEnvelope<AuthorizationApprovedV1> envelope) {
        AuthorizationApprovedV1 event = envelope.payload();
        CardAuthorizationFact fact = facts.findById(event.authorizationId())
                .orElseGet(() -> new CardAuthorizationFact(event.authorizationId()));
        fact.applyApproved(event.cardId(), event.userId(), event.accountId(), event.panLast4(),
                event.merchantName(), event.merchantMcc(), event.merchantCountry(), event.amountMinor(),
                event.currency(), event.billingMinor(), event.billingCurrency(), envelope.occurredAt());
        facts.save(fact);

        categorisation.categoriseIfAbsent(event.authorizationId(), event.userId(), event.merchantName(),
                event.merchantMcc(), envelope.occurredAt());
    }
}
