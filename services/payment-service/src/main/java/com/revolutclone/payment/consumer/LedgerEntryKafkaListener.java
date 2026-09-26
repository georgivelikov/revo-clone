package com.revolutclone.payment.consumer;

import com.revolutclone.contracts.events.EntryPostedV1;
import com.revolutclone.contracts.events.EntryRejectedV1;
import com.revolutclone.messaging.idempotent.IdempotentHandlerFactory;
import com.revolutclone.messaging.spring.EventTypeRouter;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The thin {@code @KafkaListener} for the saga's return leg. {@code ledger.entry.v1} carries the
 * two results this service waits for plus every reservation event and every entry posted by any
 * other caller of the ledger, so the listener routes on the envelope's {@code type} through
 * {@link EventTypeRouter} and ignores the rest - the two handlers then narrow further to the
 * entries that are actually payments.
 *
 * <p>Each handler is wrapped in its own {@code IdempotentHandler} before registration, sharing
 * {@code groupId=payment-service} with {@link AccountOpenedKafkaListener} - one
 * {@code processed_event} consumer group per service. That covers Kafka redelivering a single
 * event; the second, differently-shaped repeat - the ledger emitting one {@code EntryPostedV1}
 * per posting, so two per transfer - is handled by {@link PaymentResultApplier}'s state guard.
 * {@code LedgerEventRedeliveryTest} pins both.
 *
 * <p>No {@code ConsumerSeekAware} here, unlike {@link AccountOpenedKafkaListener}: that listener
 * rebuilds a projection from a compacted topic and must replay from the beginning, while this one
 * reacts to events once and must resume from its committed offset. Seeking to the beginning would
 * re-present every ledger entry ever posted - harmless for already-terminal payments thanks to the
 * state guard, but pointless work and a flood of nothing.
 */
@Component
public class LedgerEntryKafkaListener {

    /** Keyed by ledgerAccountId - see plans/phase-02-ledger.md's {@code ### Events out}. */
    private static final String TOPIC = "ledger.entry.v1";

    private static final String CONSUMER_GROUP = "payment-service";

    private final EventTypeRouter router;

    public LedgerEntryKafkaListener(EntryPostedEventHandler posted, EntryRejectedEventHandler rejected,
            IdempotentHandlerFactory factory, JsonMapper jsonMapper) {
        this.router = EventTypeRouter.builder(jsonMapper)
                .register(EntryPostedV1.class, factory.wrap(posted, CONSUMER_GROUP))
                .register(EntryRejectedV1.class, factory.wrap(rejected, CONSUMER_GROUP))
                .build();
    }

    @KafkaListener(topics = TOPIC, groupId = CONSUMER_GROUP)
    public void onMessage(String rawJson) {
        router.onMessage(rawJson);
    }
}
