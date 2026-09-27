package com.revolutclone.fx.consumer;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.LedgerAccountCreatedV1;
import com.revolutclone.fx.readmodel.LedgerAccountRef;
import com.revolutclone.fx.readmodel.LedgerAccountRefRepository;
import com.revolutclone.messaging.api.EventHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects {@link LedgerAccountCreatedV1} into the ledger-account-to-account correspondence the
 * quoting path needs to address a hold. ledger-service publishes this event so account-service can
 * fill in its own {@code ledger_account_id}; fx-service consumes it because the reservation API
 * names the ledger account while a quote request names only a currency.
 *
 * <p>Not wrapped in an {@code IdempotentHandler}, same reasoning as
 * {@link AccountOwnerEventHandler}: an upsert keyed on the ledger account id, with no side effect a
 * redelivery could repeat.
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
