package com.revolutclone.analytics.consumer;

import com.revolutclone.analytics.readmodel.LedgerEntryFact;
import com.revolutclone.analytics.readmodel.LedgerEntryFactRepository;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.EntryPostedV1;
import com.revolutclone.messaging.api.EventHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durably records one posted ledger entry as a {@link LedgerEntryFact}. Nothing beyond
 * recording happens here - task 06.1 is scaffold-only, and which later task (06.3's
 * monthly_spend, most plausibly) reads this table is deliberately not decided by this class.
 *
 * <p><strong>Deliberately not wrapped in an {@code IdempotentHandler}</strong>, same reasoning
 * as fx-service's {@code LedgerAccountCreatedEventHandler} and notification-service's own
 * projection handlers: this is an upsert keyed on the entry id, itself the event's own
 * identity, so applying the same event twice leaves exactly the same row. There is no side
 * effect here for a redelivery to repeat - unlike notification-service's handler on this same
 * event, which sends a push and therefore does need the wrapper.
 */
@Component
public class EntryPostedEventHandler implements EventHandler<EntryPostedV1> {

    private final LedgerEntryFactRepository facts;

    public EntryPostedEventHandler(LedgerEntryFactRepository facts) {
        this.facts = facts;
    }

    @Override
    @Transactional
    public void handle(EventEnvelope<EntryPostedV1> envelope) {
        EntryPostedV1 event = envelope.payload();
        LedgerEntryFact fact = facts.findById(event.entryId()).orElseGet(() -> new LedgerEntryFact(event.entryId()));
        fact.apply(event.accountId(), event.direction(), event.amountMinor(), event.currency(),
                event.balanceAfterMinor(), event.type(), event.referenceId(), envelope.occurredAt());
        facts.save(fact);
    }
}
