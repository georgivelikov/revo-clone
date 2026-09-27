package com.revolutclone.notification.rates;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
 */
@Component
public class RateTickBroadcaster {

    /** Every client subscribes here directly; no per-user destination, since a rate is nobody's secret. */
    public static final String DESTINATION = "/topic/rates";

    private final RateTickCache cache;
    private final SimpMessagingTemplate broker;
    private final Map<String, Instant> lastBroadcastAsOf = new ConcurrentHashMap<>();

    public RateTickBroadcaster(RateTickCache cache, SimpMessagingTemplate broker) {
        this.cache = cache;
        this.broker = broker;
    }

    @Scheduled(fixedDelayString = "${revolut.notifications.rates.broadcast-delay-ms:500}")
    public void broadcastChangedPairs() {
        for (RateTickCache.LatestRate tick : cache.allLatest()) {
            Instant lastSent = lastBroadcastAsOf.get(tick.pair());
            if (lastSent != null && !tick.asOf().isAfter(lastSent)) {
                continue;
            }
            broker.convertAndSend(DESTINATION, new RateTickFrame(tick.pair(), tick.mid(), tick.asOf()));
            lastBroadcastAsOf.put(tick.pair(), tick.asOf());
        }
    }
}
