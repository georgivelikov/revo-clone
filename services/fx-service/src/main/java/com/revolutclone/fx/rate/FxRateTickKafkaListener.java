package com.revolutclone.fx.rate;

import com.revolutclone.contracts.events.FxRateTickV1;
import com.revolutclone.messaging.spring.KafkaEventHandlerAdapter;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The thin {@code @KafkaListener} common-messaging's design calls for - the only place in
 * fx-service that imports a Kafka type for the rate cache. The projection itself lives in
 * {@link FxRateTickHandler} and is testable with no broker at all.
 *
 * <p><strong>{@link #onPartitionsAssigned} is the "a restart replays the tail of the topic to
 * warm up" half of plans/phase-04-fx-multicurrency.md's fx-service design detail</strong>, not an
 * optimisation. Unlike payment-service's compacted {@code account.account.v1} listener,
 * {@code fx.rate.tick.v1} is a {@code delete}-cleanup topic with a 1-hour retention (see
 * infra/kafka/create-topics.sh) - so seeking to the beginning does not replay unbounded history,
 * it replays exactly "the tail": whatever ticks still exist inside that retention window, in
 * their original per-partition order (every tick for a given pair lands on the same partition, so
 * ordering for that pair is preserved). Applying them in order through the same keyed-upsert
 * handler naturally converges the cache on each pair's actual latest mid by the time the replay
 * catches up - see {@link FxRateCache#update}. Without this, an instance whose consumer group
 * already has committed offsets past the last hour's ticks would start quoting with an empty
 * cache instead of the live rate.
 */
@Component
public class FxRateTickKafkaListener implements ConsumerSeekAware {

    /** Keyed by pair, delete-retention (not compacted) - see infra/kafka/create-topics.sh. */
    private static final String TOPIC = "fx.rate.tick.v1";

    private static final String CONSUMER_GROUP = "fx-service";

    private final KafkaEventHandlerAdapter<FxRateTickV1> adapter;

    public FxRateTickKafkaListener(FxRateTickHandler delegate, JsonMapper jsonMapper) {
        this.adapter = new KafkaEventHandlerAdapter<>(delegate, jsonMapper, FxRateTickV1.class);
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
