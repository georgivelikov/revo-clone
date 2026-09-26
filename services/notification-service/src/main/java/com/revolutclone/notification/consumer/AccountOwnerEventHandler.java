package com.revolutclone.notification.consumer;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AccountOpenedV1;
import com.revolutclone.messaging.api.EventHandler;
import com.revolutclone.notification.readmodel.AccountOwner;
import com.revolutclone.notification.readmodel.AccountOwnerRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects {@link AccountOpenedV1} into the account-to-owner read model every fan-out ends at. No
 * HTTP call to account-service: same rule, same reasoning as payment-service's own read model
 * (CLAUDE.md rule 7, and the phase's "no sideways HTTP").
 *
 * <p><strong>Deliberately not wrapped in an {@code IdempotentHandler}</strong>, unlike the handlers
 * that actually notify someone: this is a keyed upsert of facts carried entirely by the event, so a
 * second application leaves precisely the same row. Wrapping it would break the rebuild
 * {@link AccountKafkaListener} performs on every partition assignment, because {@code processed_event}
 * lives in the same database as the projection it would be guarding and would skip every event of
 * that replay.
 */
@Component
public class AccountOwnerEventHandler implements EventHandler<AccountOpenedV1> {

    private final AccountOwnerRepository accountOwners;

    public AccountOwnerEventHandler(AccountOwnerRepository accountOwners) {
        this.accountOwners = accountOwners;
    }

    @Override
    @Transactional
    public void handle(EventEnvelope<AccountOpenedV1> envelope) {
        AccountOpenedV1 event = envelope.payload();
        AccountOwner owner = accountOwners.findById(event.accountId())
                .orElseGet(() -> new AccountOwner(event.accountId()));
        owner.apply(event.userId(), event.currency(), envelope.occurredAt());
        accountOwners.save(owner);
    }
}
