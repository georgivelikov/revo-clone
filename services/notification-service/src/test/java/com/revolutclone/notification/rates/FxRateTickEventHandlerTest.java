package com.revolutclone.notification.rates;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.FxRateTickV1;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link FxRateTickEventHandler} is the only thing standing between a Kafka envelope and {@link RateTickCache}. */
class FxRateTickEventHandlerTest {

    @Test
    void aTickIsUpsertedIntoTheCacheUnderItsOwnPair() {
        RateTickCache cache = new RateTickCache();
        FxRateTickEventHandler handler = new FxRateTickEventHandler(cache);
        Instant asOf = Instant.now();

        handler.handle(envelope(new FxRateTickV1("EUR/GBP", "0.8600000000", asOf, "simulator")));

        assertThat(cache.allLatest()).contains(new RateTickCache.LatestRate("EUR/GBP", "0.8600000000", asOf,
                "simulator"));
    }

    private static EventEnvelope<FxRateTickV1> envelope(FxRateTickV1 payload) {
        return new EventEnvelope<>(UUID.randomUUID(), Instant.now(), UUID.randomUUID().toString(),
                "FxRateTickV1", 1, payload);
    }
}
