package com.revolutclone.fx.consumer;

import com.revolutclone.contracts.events.AccountOpenedV1;
import com.revolutclone.messaging.spring.KafkaEventHandlerAdapter;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The thin {@code @KafkaListener} common-messaging's design calls for - the projection itself lives
 * in {@link AccountOwnerEventHandler} and is testable with no broker at all.
 *
 * <p>{@code account.account.v1} is compacted, so its log always holds the latest event for every
 * account ever opened; seeking to the beginning on every partition assignment therefore rebuilds
 * the whole wallet projection on startup. Without it, an instance whose database is fresh but whose
 * consumer group already has committed offsets would resolve no wallets at all and reject every
 * quote as "no wallet in that currency" - see payment-service's {@code AccountOpenedKafkaListener}
 * for the same pattern and the Spring Kafka reference's own wording for this case.
 */
@Component
public class AccountKafkaListener implements ConsumerSeekAware {

    /** Keyed by accountId, compacted - see plans/phase-00-foundations.md's topic catalogue. */
    private static final String TOPIC = "account.account.v1";

    private static final String CONSUMER_GROUP = "fx-service";

    private final KafkaEventHandlerAdapter<AccountOpenedV1> adapter;

    public AccountKafkaListener(AccountOwnerEventHandler delegate, JsonMapper jsonMapper) {
        this.adapter = new KafkaEventHandlerAdapter<>(delegate, jsonMapper, AccountOpenedV1.class);
    }

    @KafkaListener(topics = TOPIC, groupId = CONSUMER_GROUP)
    public void onMessage(String rawJson) {
        adapter.onMessage(rawJson);
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        callback.seekToBeginning(assignments.keySet());
    }
}
