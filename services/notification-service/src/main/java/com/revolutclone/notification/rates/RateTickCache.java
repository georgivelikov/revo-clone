package com.revolutclone.notification.rates;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The latest rate tick per pair, kept warm by {@link FxRateTickEventHandler} and read by
 * {@link RateTickBroadcaster} - this service's own copy of the pattern fx-service's own
 * {@code FxRateCache} already uses for the identical reason: {@code fx.rate.tick.v1} ticks about
 * twice a second per pair, and nothing here needs to ask Kafka the same question again to answer
 * "what is EUR/GBP right now".
 *
 * <p>No replay-on-startup: unlike fx-service's cache, which has to be correct before it can
 * quote, this one only feeds a live ticker display - the next tick is at most half a second away,
 * so an empty cache for that long on a fresh instance is not worth a topic replay to avoid.
 */
@Component
public class RateTickCache {

    /** One rate tick, as last observed for its pair. */
    public record LatestRate(String pair, String mid, Instant asOf, String source) {
    }

    private final Map<String, LatestRate> latestByPair = new ConcurrentHashMap<>();

    /**
     * Upserts the given tick. Ignores one that is not newer than what is already stored, so
     * redelivery or an out-of-order rebalance can never move a pair backwards - the same
     * defensive merge {@code FxRateCache} uses.
     */
    public void update(LatestRate tick) {
        latestByPair.merge(tick.pair(), tick,
                (existing, incoming) -> incoming.asOf().isBefore(existing.asOf()) ? existing : incoming);
    }

    /** A snapshot of every pair currently held, for {@link RateTickBroadcaster} to scan. */
    public Collection<LatestRate> allLatest() {
        return latestByPair.values();
    }
}
