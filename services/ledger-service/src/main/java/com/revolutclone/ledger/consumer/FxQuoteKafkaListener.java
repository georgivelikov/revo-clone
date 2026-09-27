package com.revolutclone.ledger.consumer;

import com.revolutclone.contracts.events.FxExecutionRequestedV1;
import com.revolutclone.messaging.idempotent.IdempotentHandlerFactory;
import com.revolutclone.messaging.spring.EventTypeRouter;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The inbound leg of plans/phase-04-fx-multicurrency.md's execution path. {@code fx.quote.v1} is
 * a mixed topic - fx-service also publishes {@code FxExecutionFailedV1} onto it, which is this
 * service's own rejection coming back - so the listener routes on the envelope's {@code type} and
 * ignores everything it did not register, exactly as {@link PaymentEventKafkaListener} does.
 *
 * <p>{@code groupId=ledger-service} is shared with this service's other listeners: one consumer
 * group per service, so {@code processed_event} dedupes across all of them. Belt and braces with
 * the derived {@code fxquote:<quoteId>} ledger key, which catches the case
 * {@code processed_event} cannot see - a different {@code eventId} carrying the same quote, which
 * is what a republished outbox row looks like.
 *
 * <p>No seek on assignment: an execution is an instruction to move money once, not a projection
 * to rebuild, and replaying the topic from the beginning would re-present every exchange ever
 * made. (They would all be refused by the idempotency key, which is the point of having it - but
 * the committed offset is still the correct resume point.)
 */
@Component
public class FxQuoteKafkaListener {

    /** Keyed by quoteId, 30-day retention - see plans/phase-00-foundations.md's catalogue. */
    private static final String TOPIC = "fx.quote.v1";

    private static final String CONSUMER_GROUP = "ledger-service";

    private final EventTypeRouter router;

    public FxQuoteKafkaListener(FxExecutionRequestedEventHandler executionRequested,
            IdempotentHandlerFactory factory, JsonMapper jsonMapper) {
        this.router = EventTypeRouter.builder(jsonMapper)
                .register(FxExecutionRequestedV1.class, factory.wrap(executionRequested, CONSUMER_GROUP))
                .build();
    }

    @KafkaListener(topics = TOPIC, groupId = CONSUMER_GROUP)
    public void onMessage(String rawJson) {
        router.onMessage(rawJson);
    }
}
