package com.revolutclone.notification.rates;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.times;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * plans/phase-04-fx-multicurrency.md task 9's throttle, in isolation from Kafka and STOMP: a
 * scan broadcasts a pair's tick once per change and never resends an unchanged one - "send only
 * changed pairs" from the phase's own Risks &amp; gotchas.
 */
class RateTickBroadcasterTest {

    @Test
    void aFreshTickIsBroadcastButRescanningTheSameTickSendsNothingMore() {
        RateTickCache cache = new RateTickCache();
        SimpMessagingTemplate broker = mock(SimpMessagingTemplate.class);
        RateTickBroadcaster broadcaster = new RateTickBroadcaster(cache, broker);
        Instant firstTick = Instant.now();
        cache.update(new RateTickCache.LatestRate("EUR/GBP", "0.8600000000", firstTick, "test"));

        broadcaster.broadcastChangedPairs();
        broadcaster.broadcastChangedPairs();
        broadcaster.broadcastChangedPairs();

        verify(broker, times(1)).convertAndSend(eq(RateTickBroadcaster.DESTINATION),
                eq(new RateTickFrame("EUR/GBP", "0.8600000000", firstTick)));
        verifyNoMoreInteractions(broker);
    }

    @Test
    void onlyThePairThatActuallyMovedIsBroadcastOnTheNextScan() {
        RateTickCache cache = new RateTickCache();
        SimpMessagingTemplate broker = mock(SimpMessagingTemplate.class);
        RateTickBroadcaster broadcaster = new RateTickBroadcaster(cache, broker);
        Instant t0 = Instant.now();
        cache.update(new RateTickCache.LatestRate("EUR/GBP", "0.8600000000", t0, "test"));
        cache.update(new RateTickCache.LatestRate("EUR/USD", "1.0800000000", t0, "test"));
        broadcaster.broadcastChangedPairs();

        Instant t1 = t0.plusMillis(500);
        cache.update(new RateTickCache.LatestRate("EUR/USD", "1.0801000000", t1, "test"));
        broadcaster.broadcastChangedPairs();

        verify(broker, times(1)).convertAndSend(eq(RateTickBroadcaster.DESTINATION),
                eq(new RateTickFrame("EUR/GBP", "0.8600000000", t0)));
        verify(broker, times(1)).convertAndSend(eq(RateTickBroadcaster.DESTINATION),
                eq(new RateTickFrame("EUR/USD", "1.0800000000", t0)));
        verify(broker, times(1)).convertAndSend(eq(RateTickBroadcaster.DESTINATION),
                eq(new RateTickFrame("EUR/USD", "1.0801000000", t1)));
        verifyNoMoreInteractions(broker);
    }
}
