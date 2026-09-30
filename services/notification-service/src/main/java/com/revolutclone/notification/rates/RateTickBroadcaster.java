package com.revolutclone.notification.rates;

import com.revolutclone.notification.fanout.RedisFanOutRelay;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * plans/phase-04-fx-multicurrency.md task 9's throttle: "forward rate ticks to subscribed
 * clients (throttled to ~2/s)" - and the phase's own Risks &amp; gotchas is explicit about why:
 * "Rate ticks will flood the WebSocket. Throttle server-side to ~2/s per client and send only
 * changed pairs, or the browser spends its time re-rendering."
 *
 * <p>The throttle is applied once here, not per client: {@link #DESTINATION} is a single shared
 * broadcast topic, and STOMP's simple broker fans a message sent there out to every current
 * subscriber for free - so one scheduled scan of {@link RateTickCache} at the target rate
 * produces the same ~2/s ceiling for every subscriber at once, with no per-connection state to
 * keep. "Only changed pairs" is {@link #lastBroadcastAsOf}: a pair whose latest tick has not
 * moved since the previous scan is simply not resent.
 *
 * <p>Each replica's {@link RateTickCache} is only as complete as the Kafka partitions this
 * instance's consumer happens to hold, so the broadcast goes through {@link RedisFanOutRelay}
 * rather than the local broker directly - every instance's scan publishes independently, and the
 * relay's Redis round-trip is what lets every instance's sockets receive every pair regardless of
 * which replica's cache actually held it.
 */
@Component
public class RateTickBroadcaster {

    /** Every client subscribes here directly; no per-user destination, since a rate is nobody's secret. */
    public static final String DESTINATION = "/topic/rates";

    private final RateTickCache cache;
    private final RedisFanOutRelay relay;
    private final Map<String, Instant> lastBroadcastAsOf = new ConcurrentHashMap<>();

    public RateTickBroadcaster(RateTickCache cache, RedisFanOutRelay relay) {
        this.cache = cache;
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${revolut.notifications.rates.broadcast-delay-ms:500}")
    public void broadcastChangedPairs() {
        for (RateTickCache.LatestRate tick : cache.allLatest()) {
            Instant lastSent = lastBroadcastAsOf.get(tick.pair());
            if (lastSent != null && !tick.asOf().isAfter(lastSent)) {
                continue;
            }
            relay.publishRateTick(new RateTickFrame(tick.pair(), tick.mid(), tick.asOf()));
            lastBroadcastAsOf.put(tick.pair(), tick.asOf());
        }
    }
}
