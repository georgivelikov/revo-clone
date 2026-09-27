package com.revolutclone.card.consumer;

import com.revolutclone.card.readmodel.FundingAccount;
import com.revolutclone.card.readmodel.FundingAccountRepository;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AccountOpenedV1;
import com.revolutclone.messaging.api.EventHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects {@link AccountOpenedV1} into card-service's own wallet projection, so the authorization
 * chain can learn a card's funding currency without a remote call.
 *
 * <p><strong>Deliberately not wrapped in an {@code IdempotentHandler}</strong>, same reasoning as
 * fx-service's and payment-service's copies of this projection: it is a keyed upsert of facts carried
 * entirely by the event, so applying it twice leaves exactly the same row, and
 * {@link AccountKafkaListener} re-reads the whole compacted topic on every partition assignment so a
 * fresh instance rebuilds its view. A {@code processed_event} dedupe lives in this same database and
 * would skip every event of that very rebuild.
 */
@Component
public class FundingAccountEventHandler implements EventHandler<AccountOpenedV1> {

    private final FundingAccountRepository fundingAccounts;

    public FundingAccountEventHandler(FundingAccountRepository fundingAccounts) {
        this.fundingAccounts = fundingAccounts;
    }

    @Override
    @Transactional
    public void handle(EventEnvelope<AccountOpenedV1> envelope) {
        AccountOpenedV1 event = envelope.payload();
        FundingAccount account = fundingAccounts.findById(event.accountId())
                .orElseGet(() -> new FundingAccount(event.accountId()));
        account.apply(event.userId(), event.currency(), event.type(), envelope.occurredAt());
        fundingAccounts.save(account);
    }
}
