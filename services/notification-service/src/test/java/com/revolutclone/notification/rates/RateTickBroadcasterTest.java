package com.revolutclone.notification.rates;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.times;

import com.revolutclone.notification.fanout.RedisFanOutRelay;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * plans/phase-04-fx-multicurrency.md task 9's throttle, in isolation from Kafka and STOMP: a
 * scan broadcasts a pair's tick once per change and never resends an unchanged one - "send only
 * changed pairs" from the phase's own Risks &amp; gotchas.
 *
 * <p>Asserts against {@link RedisFanOutRelay} rather than a {@code SimpMessagingTemplate} mock -
 * phase 07 task 9 moved the actual STOMP send behind the relay (see that class), so this is now
 * the collaborator whose call count and arguments prove the throttle.
 */
class RateTickBroadcasterTest {

    @Test
    void aFreshTickIsBroadcastButRescanningTheSameTickSendsNothingMore() {
        RateTickCache cache = new RateTickCache();
        RedisFanOutRelay relay = mock(RedisFanOutRelay.class);
        RateTickBroadcaster broadcaster = new RateTickBroadcaster(cache, relay);
        Instant firstTick = Instant.now();
        cache.update(new RateTickCache.LatestRate("EUR/GBP", "0.8600000000", firstTick, "test"));

        broadcaster.broadcastChangedPairs();
        broadcaster.broadcastChangedPairs();
        broadcaster.broadcastChangedPairs();

        verify(relay, times(1)).publishRateTick(eq(new RateTickFrame("EUR/GBP", "0.8600000000", firstTick)));
        verifyNoMoreInteractions(relay);
    }

    @Test
    void onlyThePairThatActuallyMovedIsBroadcastOnTheNextScan() {
        RateTickCache cache = new RateTickCache();
        RedisFanOutRelay relay = mock(RedisFanOutRelay.class);
        RateTickBroadcaster broadcaster = new RateTickBroadcaster(cache, relay);
        Instant t0 = Instant.now();
        cache.update(new RateTickCache.LatestRate("EUR/GBP", "0.8600000000", t0, "test"));
        cache.update(new RateTickCache.LatestRate("EUR/USD", "1.0800000000", t0, "test"));
        broadcaster.broadcastChangedPairs();

        Instant t1 = t0.plusMillis(500);
        cache.update(new RateTickCache.LatestRate("EUR/USD", "1.0801000000", t1, "test"));
        broadcaster.broadcastChangedPairs();

        verify(relay, times(1)).publishRateTick(eq(new RateTickFrame("EUR/GBP", "0.8600000000", t0)));
        verify(relay, times(1)).publishRateTick(eq(new RateTickFrame("EUR/USD", "1.0800000000", t0)));
        verify(relay, times(1)).publishRateTick(eq(new RateTickFrame("EUR/USD", "1.0801000000", t1)));
        verifyNoMoreInteractions(relay);
    }
}
