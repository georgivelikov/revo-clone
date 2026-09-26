package com.revolutclone.notification.consumer;

import com.revolutclone.contracts.events.AccountOpenedV1;
import com.revolutclone.messaging.spring.KafkaEventHandlerAdapter;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The thin {@code @KafkaListener} for the account read model - the projection itself is in
 * {@link AccountOwnerEventHandler} and needs no broker to test.
 *
 * <p>Seeks to the beginning on every partition assignment, exactly as payment-service's equivalent
 * listener does and for the same reason: {@code account.account.v1} is compacted, so its log always
 * holds the latest event for every account ever opened, and replaying it rebuilds this service's
 * whole view. Without it an instance with a fresh database but a consumer group that already has
 * committed offsets - a restored backup, a recreated volume, a new environment against an existing
 * broker - would know nobody, and every push would go nowhere with no error to explain it. Spring
 * Kafka's reference documentation names this case for {@code ConsumerSeekAware} verbatim; see
 * docs/spring-boot-4-notes.md.
 */
@Component
public class AccountKafkaListener implements ConsumerSeekAware {

    /** Keyed by accountId, compacted - see plans/phase-00-foundations.md's topic catalogue. */
    private static final String TOPIC = "account.account.v1";

    private final KafkaEventHandlerAdapter<AccountOpenedV1> adapter;

    public AccountKafkaListener(AccountOwnerEventHandler delegate, JsonMapper jsonMapper) {
        this.adapter = new KafkaEventHandlerAdapter<>(delegate, jsonMapper, AccountOpenedV1.class);
    }

    @KafkaListener(topics = TOPIC, groupId = NotificationConsumerGroup.NAME)
    public void onMessage(String rawJson) {
        adapter.onMessage(rawJson);
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        callback.seekToBeginning(assignments.keySet());
    }
}
