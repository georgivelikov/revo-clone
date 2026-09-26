package com.revolutclone.notification.consumer;

import com.revolutclone.contracts.events.EntryPostedV1;
import com.revolutclone.contracts.events.LedgerAccountCreatedV1;
import com.revolutclone.messaging.idempotent.IdempotentHandlerFactory;
import com.revolutclone.messaging.spring.EventTypeRouter;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code ledger.entry.v1} carries every ledger event there is, so the listener routes on the
 * envelope's {@code type} and ignores the rest - reservations, rejections, drift reports. Two types
 * matter here, for different reasons: {@link EntryPostedV1} is a notification, and
 * {@link LedgerAccountCreatedV1} is the mapping that makes the first one attributable to a human.
 *
 * <p>Only the notifying handler is wrapped for idempotency. {@code processed_event} exists to stop a
 * redelivered event repeating a side effect, and a duplicate feed row plus a duplicate push is
 * exactly the side effect worth stopping; the projection has none to repeat.
 *
 * <p>No {@code ConsumerSeekAware} here, unlike {@link AccountKafkaListener}: this topic is not
 * compacted and these events are reactions, not state. Replaying it from the beginning would re-post
 * every notification this service has ever sent - harmless to the database thanks to
 * {@code processed_event}, but it would still mean a rebuild had to be deduped rather than simply
 * being correct, which is the trade the compacted-topic replay only gets away with because that
 * handler notifies nobody.
 */
@Component
public class LedgerEntryKafkaListener {

    /** Keyed by ledgerAccountId - see plans/phase-02-ledger.md's {@code ### Events out}. */
    private static final String TOPIC = "ledger.entry.v1";

    private final EventTypeRouter router;

    public LedgerEntryKafkaListener(EntryPostedEventHandler posted, LedgerAccountCreatedEventHandler accountCreated,
            IdempotentHandlerFactory factory, JsonMapper jsonMapper) {
        this.router = EventTypeRouter.builder(jsonMapper)
                .register(EntryPostedV1.class, factory.wrap(posted, NotificationConsumerGroup.NAME))
                .register(LedgerAccountCreatedV1.class, accountCreated)
                .build();
    }

    @KafkaListener(topics = TOPIC, groupId = NotificationConsumerGroup.NAME)
    public void onMessage(String rawJson) {
        router.onMessage(rawJson);
    }
}
