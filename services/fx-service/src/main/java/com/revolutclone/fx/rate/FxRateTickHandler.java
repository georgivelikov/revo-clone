package com.revolutclone.fx.rate;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.FxRateTickV1;
import com.revolutclone.messaging.api.EventHandler;
import org.springframework.stereotype.Component;

/**
 * Projects {@link FxRateTickV1} into {@link FxRateCache} - business logic here, Kafka binding in
 * {@link FxRateTickKafkaListener}, same split every other consumer in this codebase uses (see
 * payment-service's {@code AccountOpenedEventHandler}).
 *
 * <p><strong>Deliberately not wrapped in an {@code IdempotentHandler}</strong>, for the same
 * reason as that account read-model handler: this is a keyed upsert of the single fact a tick
 * carries (the current mid), so re-applying the same or an older tick is harmless, and
 * {@link FxRateTickKafkaListener} re-reads the topic's whole retention window on every partition
 * assignment so a fresh instance warms up from scratch. A {@code processed_event} dedupe living
 * in this same database would skip every event of that very warm-up.
 */
@Component
public class FxRateTickHandler implements EventHandler<FxRateTickV1> {

    private final FxRateCache cache;

    public FxRateTickHandler(FxRateCache cache) {
        this.cache = cache;
    }

    @Override
    public void handle(EventEnvelope<FxRateTickV1> envelope) {
        FxRateTickV1 tick = envelope.payload();
        cache.update(new FxRateCache.LatestRate(tick.pair(), tick.mid(), tick.asOf(), tick.source()));
    }
}
