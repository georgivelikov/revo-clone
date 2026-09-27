package com.revolutclone.fx.consumer;

import com.revolutclone.contracts.events.EntryRejectedV1;
import com.revolutclone.contracts.events.LedgerAccountCreatedV1;
import com.revolutclone.messaging.idempotent.IdempotentHandlerFactory;
import com.revolutclone.messaging.spring.EventTypeRouter;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code ledger.entry.v1} carries every ledger event there is - postings, rejections, every
 * reservation transition - so the listener routes on the envelope's {@code type} and ignores the
 * rest. Exactly one type matters to fx-service today: {@link LedgerAccountCreatedV1}, the mapping
 * that lets a quote's hold be addressed by ledger account id.
 *
 * <p>Seeks to the beginning on assignment, unlike notification-service's listener on this same
 * topic. The difference is what is registered here: that service also posts notifications from this
 * topic, where a replay would re-send every push it has ever sent, whereas the account mapping is a
 * keyed projection whose replay is simply correct. This topic is {@code delete}-retention rather
 * than compacted (infra/kafka/create-topics.sh) with unlimited retention, so the replay covers
 * every mapping ever published.
 *
 * <p>That is also why {@link EntryRejectedV1} is registered <strong>through the idempotent
 * wrapper</strong> while {@link LedgerAccountCreatedV1} is not. Compensation is a side effect, not
 * a projection: replaying it would cancel quotes that have long since been dealt with. Two
 * defences, since neither alone is enough here - {@code processed_event} stops the replay, and
 * {@code compensate}'s transition is conditional on the quote still being {@code CONSUMED} in case
 * the ledger republishes a rejection under a new {@code eventId}.
 */
@Component
public class LedgerEntryKafkaListener implements ConsumerSeekAware {

    /** Keyed by ledgerAccountId - see plans/phase-02-ledger.md's {@code ### Events out}. */
    private static final String TOPIC = "ledger.entry.v1";

    private static final String CONSUMER_GROUP = "fx-service";

    private final EventTypeRouter router;

    public LedgerEntryKafkaListener(LedgerAccountCreatedEventHandler accountCreated,
            EntryRejectedEventHandler entryRejected, IdempotentHandlerFactory factory, JsonMapper jsonMapper) {
        this.router = EventTypeRouter.builder(jsonMapper)
                .register(LedgerAccountCreatedV1.class, accountCreated)
                .register(EntryRejectedV1.class, factory.wrap(entryRejected, CONSUMER_GROUP))
                .build();
    }

    @KafkaListener(topics = TOPIC, groupId = CONSUMER_GROUP)
    public void onMessage(String rawJson) {
        router.onMessage(rawJson);
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        callback.seekToBeginning(assignments.keySet());
    }
}
