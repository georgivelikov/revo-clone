package com.revolutclone.notification.rates;

import com.revolutclone.contracts.events.FxRateTickV1;
import com.revolutclone.messaging.spring.KafkaEventHandlerAdapter;
import com.revolutclone.notification.consumer.NotificationConsumerGroup;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The thin {@code @KafkaListener} for {@link RateTickCache} - plans/phase-04-fx-multicurrency.md
 * task 9: "forward rate ticks to subscribed clients."
 *
 * <p>No seek-to-beginning, unlike {@code AccountKafkaListener}: {@code fx.rate.tick.v1} is a live
 * feed with 1 hour retention, not a compacted projection this service must fully rebuild to be
 * correct - the next tick for every pair is at most half a second away regardless of where this
 * consumer group's offset resumes.
 */
@Component
public class FxRateTickKafkaListener {

    /** Keyed by pair, 1 hour retention - plans/phase-00-foundations.md's topic catalogue. */
    private static final String TOPIC = "fx.rate.tick.v1";

    private final KafkaEventHandlerAdapter<FxRateTickV1> adapter;

    public FxRateTickKafkaListener(FxRateTickEventHandler delegate, JsonMapper jsonMapper) {
        this.adapter = new KafkaEventHandlerAdapter<>(delegate, jsonMapper, FxRateTickV1.class);
    }

    @KafkaListener(topics = TOPIC, groupId = NotificationConsumerGroup.NAME)
    public void onMessage(String rawJson) {
        adapter.onMessage(rawJson);
    }
}
