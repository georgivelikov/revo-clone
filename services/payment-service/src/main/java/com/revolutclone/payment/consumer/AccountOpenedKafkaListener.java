package com.revolutclone.payment.consumer;

import com.revolutclone.contracts.events.AccountOpenedV1;
import com.revolutclone.messaging.spring.KafkaEventHandlerAdapter;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The thin {@code @KafkaListener} common-messaging's design calls for - the only place in
 * payment-service that imports a Kafka type for the account read model. The projection itself
 * lives in {@link AccountOpenedEventHandler} and is testable with no broker at all.
 *
 * <p><strong>{@link #onPartitionsAssigned} is the "rebuilds its view on startup" half of the
 * phase's design detail</strong>, not an optimisation. {@code account.account.v1} is a
 * compacted topic (see infra/kafka/create-topics.sh), so its log always holds the latest event
 * for every account that has ever been opened; seeking to the beginning on assignment therefore
 * replays the full current state of every account into this service's read model every time a
 * container starts. Without it, an instance whose database is fresh but whose consumer group
 * already has committed offsets - a restored backup, a recreated volume, a new environment
 * pointed at an existing broker - would start with an empty view and reject perfectly valid
 * transfers as "account not found". Spring Kafka's own reference documentation names exactly
 * this case for {@code ConsumerSeekAware}: "processing a compacted topic and you wish to seek
 * to the beginning every time the application is started"
 * (<a href="https://docs.spring.io/spring-kafka/reference/kafka/seek.html">seek.html</a>).
 *
 * <p>Replaying costs nothing in correctness because the projection is a keyed upsert - see the
 * note on {@link AccountOpenedEventHandler} about why this one consumer is not wrapped in an
 * {@code IdempotentHandler}. It does mean the read model is repopulated on every rebalance, not
 * only on cold start; on a compacted topic of one message per account that is a bounded, cheap
 * read, and it is what makes the view self-healing.
 */
@Component
public class AccountOpenedKafkaListener implements ConsumerSeekAware {

    /** Keyed by accountId, compacted - see plans/phase-00-foundations.md's topic catalogue. */
    private static final String TOPIC = "account.account.v1";

    private static final String CONSUMER_GROUP = "payment-service";

    private final KafkaEventHandlerAdapter<AccountOpenedV1> adapter;

    public AccountOpenedKafkaListener(AccountOpenedEventHandler delegate, JsonMapper jsonMapper) {
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
