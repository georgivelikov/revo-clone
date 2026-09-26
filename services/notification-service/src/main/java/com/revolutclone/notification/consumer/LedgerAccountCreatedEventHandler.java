package com.revolutclone.notification.consumer;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.LedgerAccountCreatedV1;
import com.revolutclone.messaging.api.EventHandler;
import com.revolutclone.notification.readmodel.LedgerAccountRef;
import com.revolutclone.notification.readmodel.LedgerAccountRefRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects {@link LedgerAccountCreatedV1} into the ledger-account-to-account correspondence that
 * {@link EntryPostedEventHandler} needs. ledger-service publishes this event so account-service can
 * fill in its own {@code ledger_account_id}; this service consumes it because {@code EntryPostedV1}
 * names only the ledger account, and a socket is addressed by user.
 *
 * <p>Not wrapped in an {@code IdempotentHandler}, same reasoning as {@link AccountOwnerEventHandler}:
 * an upsert keyed on the ledger account id, carrying no side effect a redelivery could repeat.
 */
@Component
public class LedgerAccountCreatedEventHandler implements EventHandler<LedgerAccountCreatedV1> {

    private final LedgerAccountRefRepository ledgerAccountRefs;

    public LedgerAccountCreatedEventHandler(LedgerAccountRefRepository ledgerAccountRefs) {
        this.ledgerAccountRefs = ledgerAccountRefs;
    }

    @Override
    @Transactional
    public void handle(EventEnvelope<LedgerAccountCreatedV1> envelope) {
        LedgerAccountCreatedV1 event = envelope.payload();
        LedgerAccountRef ref = ledgerAccountRefs.findById(event.ledgerAccountId())
                .orElseGet(() -> new LedgerAccountRef(event.ledgerAccountId()));
        ref.apply(event.accountId(), envelope.occurredAt());
        ledgerAccountRefs.save(ref);
    }
}
