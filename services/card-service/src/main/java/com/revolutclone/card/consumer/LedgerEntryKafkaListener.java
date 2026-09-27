package com.revolutclone.card.consumer;

import com.revolutclone.contracts.events.LedgerAccountCreatedV1;
import com.revolutclone.messaging.spring.EventTypeRouter;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code ledger.entry.v1} carries every ledger event there is - postings, rejections, every
 * reservation transition - so this routes on the envelope's {@code type} and ignores the rest. Exactly
 * one type matters to card-service today: {@link LedgerAccountCreatedV1}, the mapping that lets a
 * card's hold be addressed by ledger account id.
 *
 * <p>Seeks to the beginning on assignment because that one registration is a keyed projection whose
 * replay is simply correct. The topic is {@code delete}-retention with unlimited retention
 * (infra/kafka/create-topics.sh), so the replay covers every mapping ever published. When the
 * settlement consumer (task 05.6) registers reservation events here, those will need the idempotent
 * wrapper for the reason fx-service's listener on this topic spells out: a capture is a side effect,
 * and replaying one is not.
 */
@Component
public class LedgerEntryKafkaListener implements ConsumerSeekAware {

    /** Keyed by ledgerAccountId - see plans/phase-02-ledger.md's {@code ### Events out}. */
    private static final String TOPIC = "ledger.entry.v1";

    private static final String CONSUMER_GROUP = "card-service";

    private final EventTypeRouter router;

    public LedgerEntryKafkaListener(LedgerAccountRefEventHandler accountCreated, JsonMapper jsonMapper) {
        this.router = EventTypeRouter.builder(jsonMapper)
                .register(LedgerAccountCreatedV1.class, accountCreated)
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
