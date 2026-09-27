package com.revolutclone.card.consumer;

import com.revolutclone.card.readmodel.LedgerAccountRef;
import com.revolutclone.card.readmodel.LedgerAccountRefRepository;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.LedgerAccountCreatedV1;
import com.revolutclone.messaging.api.EventHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects {@link LedgerAccountCreatedV1}: the account-to-ledger-account mapping a hold has to be
 * addressed by. A keyed upsert, so replay is simply correct - see
 * {@link FundingAccountEventHandler} for why that means no idempotent wrapper.
 */
@Component
public class LedgerAccountRefEventHandler implements EventHandler<LedgerAccountCreatedV1> {

    private final LedgerAccountRefRepository ledgerAccountRefs;

    public LedgerAccountRefEventHandler(LedgerAccountRefRepository ledgerAccountRefs) {
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
