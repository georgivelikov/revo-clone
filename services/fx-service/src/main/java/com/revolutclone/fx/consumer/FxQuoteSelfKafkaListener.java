package com.revolutclone.fx.consumer;

import com.revolutclone.contracts.events.FxExecutionRequestedV1;
import com.revolutclone.messaging.spring.EventTypeRouter;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * fx-service's own consumer of the very topic it publishes {@link FxExecutionRequestedV1} and
 * {@code FxExecutionFailedV1} to - see {@link QuoteConsumptionSyncHandler} for why a service
 * consumes its own outbox topic here. {@code FxExecutionFailedV1} needs no handler registered:
 * it is this service's own announcement of a compensation it has already applied to itself.
 *
 * <p>{@code groupId=fx-service}, shared with this service's other listeners on other topics - one
 * {@code processed_event} consumer group per service, though this particular handler does not use
 * that table at all (see {@link QuoteConsumptionSyncHandler}'s own idempotency reasoning). No
 * seek-to-beginning: an execution is a one-time instruction, not a projection to rebuild.
 */
@Component
public class FxQuoteSelfKafkaListener {

    private static final String TOPIC = "fx.quote.v1";

    private static final String CONSUMER_GROUP = "fx-service";

    private final EventTypeRouter router;

    public FxQuoteSelfKafkaListener(QuoteConsumptionSyncHandler handler, JsonMapper jsonMapper) {
        this.router = EventTypeRouter.builder(jsonMapper)
                .register(FxExecutionRequestedV1.class, handler)
                .build();
    }

    @KafkaListener(topics = TOPIC, groupId = CONSUMER_GROUP)
    public void onMessage(String rawJson) {
        router.onMessage(rawJson);
    }
}
