package com.revolutclone.account.consumer;

import com.revolutclone.contracts.events.LedgerAccountCreatedV1;
import com.revolutclone.messaging.spring.EventTypeRouter;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code ledger.entry.v1} carries every entry the ledger ever posts, every reservation event
 * and (as of phase 04) FX execution events - none of that is this service's business except one
 * thing, {@link LedgerAccountCreatedV1}, so the router registers only that type and silently
 * ignores the rest.
 *
 * <p>Seeks to the beginning on every partition assignment, exactly as fx-service's identical
 * listener does and for the same reason: {@link LedgerAccountCreatedEventHandler} is a keyed
 * projection, not a one-time side effect, so a fresh database with a consumer group that
 * already has committed offsets (a restored backup, a recreated volume, a new environment
 * against an existing broker) must replay the whole topic to fill in every account's
 * {@code ledgerAccountId}, not just the ones opened after this instance started.
 */
@Component
public class LedgerEntryKafkaListener implements ConsumerSeekAware {

    /** Keyed by ledgerAccountId - see plans/phase-02-ledger.md's {@code ### Events out}. */
    private static final String TOPIC = "ledger.entry.v1";

    private static final String CONSUMER_GROUP = "account-service";

    private final EventTypeRouter router;

    public LedgerEntryKafkaListener(LedgerAccountCreatedEventHandler ledgerAccountCreated, JsonMapper jsonMapper) {
        this.router = EventTypeRouter.builder(jsonMapper)
                .register(LedgerAccountCreatedV1.class, ledgerAccountCreated)
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
