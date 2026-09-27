package com.revolutclone.card.authz;

import com.revolutclone.card.domain.CardAuthorization;
import com.revolutclone.card.domain.CardAuthorizationRepository;
import com.revolutclone.contracts.DomainEvent;
import com.revolutclone.messaging.api.OutboxAppender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one transactional write of an authorization decision: the {@code card_authorization} row and
 * the event that announces it, together or not at all (CLAUDE.md rule 6).
 *
 * <p><strong>Its own bean, and only these two methods, on purpose.</strong> Step 6 of the chain is an
 * HTTP call to ledger-service, and it must not happen inside this transaction - a database
 * transaction cannot roll a hold back, and holding one open across a network call would widen exactly
 * the window that hurts. {@link AuthorizationService} therefore holds the funds first with no
 * transaction open and calls in here afterwards; a private {@code @Transactional} method on that
 * class would have been silently non-transactional anyway, since Spring's proxy never sees a
 * self-call.
 *
 * <p>The cost of that ordering is the same one fx-service's quoting path accepts: a crash between the
 * hold and this write strands a hold, which the ledger's own expiry releases. A hold with no record
 * is recoverable; a record with no hold would be an approval with nothing behind it.
 */
@Component
public class AuthorizationRecorder {

    /**
     * The only card topic plans/phase-00-foundations.md's catalogue declares - it carries the
     * network's request and both of the issuer's answers, the same way {@code ledger.entry.v1}
     * carries every ledger event type.
     */
    static final String CARD_TOPIC = "card.authorization.v1";

    private final CardAuthorizationRepository authorizations;
    private final OutboxAppender outbox;

    public AuthorizationRecorder(CardAuthorizationRepository authorizations, OutboxAppender outbox) {
        this.authorizations = authorizations;
        this.outbox = outbox;
    }

    /**
     * Records a decision made about a card that exists, approved or declined.
     *
     * <p>Keyed by {@code cardId}: "two taps on the same card are evaluated in order against the same
     * limits" (CLAUDE.md rule 5, and the phase's own justification for it). {@code saveAndFlush} so a
     * resent {@code networkRef} that raced past {@link AuthorizationService}'s own check fails here, on
     * the unique index, rather than at commit with an outbox row already appended.
     */
    @Transactional
    public void record(CardAuthorization authorization, DomainEvent decision) {
        authorizations.saveAndFlush(authorization);
        outbox.append(CARD_TOPIC, authorization.getCardId().toString(), decision);
    }

    /**
     * Records the one decision that has no row: a PAN that resolves to no card. {@code
     * card_authorization.card_id} is {@code not null} and references {@code card(id)}, so there is
     * nothing to insert - but "the network presented a card number we never issued" is still worth
     * publishing, keyed by the network's own reference since there is no card id to key it by.
     */
    @Transactional
    public void recordUnknownCard(String networkRef, DomainEvent decision) {
        outbox.append(CARD_TOPIC, networkRef, decision);
    }
}
