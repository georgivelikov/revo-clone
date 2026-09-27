package com.revolutclone.card.consumer;

import com.revolutclone.contracts.events.AccountOpenedV1;
import com.revolutclone.messaging.spring.KafkaEventHandlerAdapter;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code account.account.v1} is compacted, so its log always holds the latest event for every account
 * ever opened; seeking to the beginning on every partition assignment therefore rebuilds the whole
 * wallet projection on startup. Without it, an instance whose database is fresh but whose consumer
 * group already has committed offsets would resolve no wallet for any card and fail every
 * authorization - see fx-service's and payment-service's listeners on this same topic.
 */
@Component
public class AccountKafkaListener implements ConsumerSeekAware {

    /** Keyed by accountId, compacted - see plans/phase-00-foundations.md's topic catalogue. */
    private static final String TOPIC = "account.account.v1";

    private static final String CONSUMER_GROUP = "card-service";

    private final KafkaEventHandlerAdapter<AccountOpenedV1> adapter;

    public AccountKafkaListener(FundingAccountEventHandler delegate, JsonMapper jsonMapper) {
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
