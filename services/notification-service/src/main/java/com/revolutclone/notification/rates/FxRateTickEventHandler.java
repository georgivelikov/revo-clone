package com.revolutclone.notification.rates;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.FxRateTickV1;
import com.revolutclone.messaging.api.EventHandler;
import org.springframework.stereotype.Component;

/** Keeps {@link RateTickCache} warm. Nothing here needs {@code processed_event}: a stale or
 * redelivered tick is simply a no-op merge, and a live ticker has nothing to compensate. */
@Component
public class FxRateTickEventHandler implements EventHandler<FxRateTickV1> {

    private final RateTickCache cache;

    public FxRateTickEventHandler(RateTickCache cache) {
        this.cache = cache;
    }

    @Override
    public void handle(EventEnvelope<FxRateTickV1> envelope) {
        FxRateTickV1 tick = envelope.payload();
        cache.update(new RateTickCache.LatestRate(tick.pair(), tick.mid(), tick.asOf(), tick.source()));
    }
}
