package com.revolutclone.card.settlement;

import com.revolutclone.card.domain.CardAuthorization;
import com.revolutclone.card.domain.CardAuthorizationRepository;
import com.revolutclone.contracts.DomainEvent;
import com.revolutclone.messaging.api.OutboxAppender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one transactional write of a settlement or reversal: the updated {@code card_authorization} row
 * and the event that announces it, together or not at all (CLAUDE.md rule 6).
 *
 * <p>Its own bean for the same reason {@code AuthorizationRecorder} is: the ledger call that precedes
 * it is HTTP, and a database transaction held open across a network call cannot roll that call back
 * anyway - it only widens the window in which it hurts. {@link SettlementService} therefore posts to
 * the ledger first with no transaction open and calls in here afterwards. A private
 * {@code @Transactional} method on that class would have been silently non-transactional regardless,
 * since Spring's proxy never sees a self-call.
 *
 * <p>The cost of that ordering is a crash between the posting and this write, which leaves a posted
 * settlement and a row that still says pending. The recovery is the redelivered message: the ledger's
 * entry is keyed on {@code cardauth:&lt;networkRef&gt;}, so settling again returns the entry that
 * already exists rather than posting a second one, and this write then catches up. That is why the
 * ordering is this way round and not the other - a row claiming a posting that does not exist has no
 * such recovery, because nothing would ever go looking for it.
 */
@Component
public class SettlementRecorder {

    /** The same single card topic the authorization decisions are published on, keyed by cardId. */
    static final String CARD_TOPIC = "card.authorization.v1";

    private final CardAuthorizationRepository authorizations;
    private final OutboxAppender outbox;

    public SettlementRecorder(CardAuthorizationRepository authorizations, OutboxAppender outbox) {
        this.authorizations = authorizations;
        this.outbox = outbox;
    }

    @Transactional
    public void record(CardAuthorization authorization, DomainEvent event) {
        authorizations.saveAndFlush(authorization);
        outbox.append(CARD_TOPIC, authorization.getCardId().toString(), event);
    }
}
