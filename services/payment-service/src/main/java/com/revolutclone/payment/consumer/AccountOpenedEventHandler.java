package com.revolutclone.payment.consumer;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AccountOpenedV1;
import com.revolutclone.messaging.api.EventHandler;
import com.revolutclone.payment.readmodel.AccountView;
import com.revolutclone.payment.readmodel.AccountViewRepository;
import com.revolutclone.payment.readmodel.AccountViewState;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects {@link AccountOpenedV1} into payment-service's local account read model - the
 * "built by consuming {@code account.account.v1}, not a synchronous call to account-service"
 * half of plans/phase-03-payments-and-ui.md's payment-service design detail. Business logic
 * here, Kafka in {@link AccountOpenedKafkaListener}, same split every other consumer in this
 * codebase uses.
 *
 * <p>The account is recorded {@code ACTIVE} because that is what account-service's
 * {@code AccountWriter} opens every account as, and {@link AccountOpenedV1} carries no state
 * field of its own. When a freeze/close event joins the topic, it gets its own handler that
 * moves the same row to {@link AccountViewState#FROZEN}/{@code CLOSED}; nothing here needs to
 * change, which is the point of keying the row on {@code accountId}.
 *
 * <p><strong>Deliberately not wrapped in an {@code IdempotentHandler}</strong>, unlike every
 * other consumer so far. The {@code processed_event} table exists to stop a redelivered event
 * from repeating a side effect, and this handler has none to repeat: it is a keyed upsert of
 * facts carried entirely by the event, so the second application of an event leaves precisely
 * the same row as the first. Wrapping it would actively break the design the phase asks for -
 * {@link AccountOpenedKafkaListener} re-reads the whole compacted topic on every partition
 * assignment so a fresh instance rebuilds its view, and a dedupe table (which lives in this
 * same database, so it survives exactly as long as the projection it would be guarding) would
 * skip every event of that rebuild.
 */
@Component
public class AccountOpenedEventHandler implements EventHandler<AccountOpenedV1> {

    private final AccountViewRepository accountViews;

    public AccountOpenedEventHandler(AccountViewRepository accountViews) {
        this.accountViews = accountViews;
    }

    @Override
    @Transactional
    public void handle(EventEnvelope<AccountOpenedV1> envelope) {
        AccountOpenedV1 event = envelope.payload();
        AccountView view = accountViews.findById(event.accountId())
                .orElseGet(() -> new AccountView(event.accountId()));
        view.apply(event.userId(), event.currency(), event.type(), AccountViewState.ACTIVE,
                envelope.occurredAt());
        accountViews.save(view);
    }
}
