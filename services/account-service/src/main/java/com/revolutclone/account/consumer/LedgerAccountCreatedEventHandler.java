package com.revolutclone.account.consumer;

import com.revolutclone.account.domain.AccountRepository;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.LedgerAccountCreatedV1;
import com.revolutclone.messaging.api.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fills in {@code Account.ledgerAccountId} - plans/phase-01-identity-account-gateway.md's own
 * words for that column, "Filled in by Phase 02 once the ledger exists." ledger-service
 * publishes {@link LedgerAccountCreatedV1} on {@code ledger.entry.v1} for exactly this reason
 * (see fx-service's identical {@code LedgerAccountRef}, which projects the same event for its
 * own quoting path); this is account-service's own side of the same fact.
 *
 * <p>Naturally idempotent - re-applying the same {@code ledgerAccountId} to the same account is
 * a no-op in effect - so this is registered without the {@code processed_event} wrapper, the
 * same reasoning fx-service's own {@code LedgerAccountCreatedEventHandler} gives for its
 * registration: a keyed projection whose replay is simply correct.
 */
@Component
public class LedgerAccountCreatedEventHandler implements EventHandler<LedgerAccountCreatedV1> {

    private static final Logger log = LoggerFactory.getLogger(LedgerAccountCreatedEventHandler.class);

    private final AccountRepository accounts;

    public LedgerAccountCreatedEventHandler(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    @Transactional
    public void handle(EventEnvelope<LedgerAccountCreatedV1> envelope) {
        LedgerAccountCreatedV1 event = envelope.payload();
        int updated = accounts.applyLedgerAccountId(event.accountId(), event.ledgerAccountId());
        if (updated == 0) {
            // This service's own account row is the one fact ledger-service does not have -
            // it only knows the external account id this event names. An id this service has
            // never heard of means its own AccountOpenedV1 has not been consumed here yet, or
            // never will be (a stale event from a different environment's topic); either way
            // there is nothing to update, and no ambiguity worth throwing over.
            log.warn("no account {} to attach ledger account {} to", event.accountId(), event.ledgerAccountId());
        }
    }
}
