package com.revolutclone.fx.consumer;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AccountOpenedV1;
import com.revolutclone.fx.readmodel.AccountOwner;
import com.revolutclone.fx.readmodel.AccountOwnerRepository;
import com.revolutclone.messaging.api.EventHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects {@link AccountOpenedV1} into fx-service's own wallet projection, so quoting can resolve
 * the caller's sell wallet locally. Business logic here, Kafka binding in
 * {@link AccountKafkaListener}, same split every other consumer in this codebase uses.
 *
 * <p><strong>Deliberately not wrapped in an {@code IdempotentHandler}</strong>, same reasoning as
 * {@code FxRateTickHandler} and payment-service's account read model: this is a keyed upsert of
 * facts carried entirely by the event, so applying it twice leaves exactly the same row, and
 * {@link AccountKafkaListener} re-reads the whole compacted topic on every partition assignment so
 * a fresh instance rebuilds its view. A {@code processed_event} dedupe lives in this same database
 * and so would skip every event of that very rebuild.
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
        owner.apply(event.userId(), event.currency(), event.type(), envelope.occurredAt());
        accountOwners.save(owner);
    }
}
