package com.revolutclone.fx.rate;

import com.revolutclone.money.FxMath;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The latest mid rate per canonical pair, kept warm by {@link FxRateTickHandler} and read by the
 * quoting path (task 04.4) instead of the {@code fx_rate} table - see
 * plans/phase-04-fx-multicurrency.md's fx-service design detail: "Reads for quoting come from
 * memory; a restart replays the tail of the topic to warm up."
 *
 * <p>Only ever stores the canonical direction a tick actually arrived on (e.g. {@code "EUR/GBP"},
 * never {@code "GBP/EUR"}). The reverse direction is derived on every lookup via
 * {@link FxMath#invert} rather than cached under its own key - the phase's own rationale:
 * "Storing both directions independently guarantees they drift apart and produce arbitrage
 * within your own system." Deriving on every call means the two directions can never disagree.
 */
@Component
public class FxRateCache {

    /** One rate tick, as last observed for its canonical pair. */
    public record LatestRate(String pair, String mid, Instant asOf, String source) {
    }

    private final Map<String, LatestRate> latestByPair = new ConcurrentHashMap<>();

    /**
     * Upserts the given tick under its own canonical pair. Ignores a tick that is not newer than
     * what is already stored, so a topic replay racing a live tick (or redelivery on a rebalance)
     * can never move the cache backwards - the partitioner keys every tick for a pair to the same
     * partition, so in-order consumption already guarantees this in the common case; this is the
     * defensive backstop for the uncommon one.
     */
    public void update(LatestRate tick) {
        latestByPair.merge(tick.pair(), tick,
                (existing, incoming) -> incoming.asOf().isBefore(existing.asOf()) ? existing : incoming);
    }

    /** The canonical-pair tick as last observed, with no direction adjustment. */
    public Optional<LatestRate> latest(String canonicalPair) {
        return Optional.ofNullable(latestByPair.get(canonicalPair));
    }

    /** A snapshot of every pair currently held, for {@link FxRateSampler} to persist. */
    public Collection<LatestRate> allLatest() {
        return latestByPair.values();
    }

    /**
     * The mid rate to sell {@code base} for {@code quote}, in whichever direction was actually
     * requested - derived by inversion if the canonical pair runs the other way. Empty if no tick
     * has ever been seen for either currency of this pair.
     */
    public Optional<String> mid(String base, String quote) {
        String upperBase = base.toUpperCase(Locale.ROOT);
        String upperQuote = quote.toUpperCase(Locale.ROOT);
        String canonical = canonicalPair(upperBase, upperQuote);

        return latest(canonical).map(stored -> {
            String requested = upperBase + "/" + upperQuote;
            return requested.equals(canonical) ? stored.mid() : FxMath.invert(stored.mid());
        });
    }

    static String canonicalPair(String base, String quote) {
        return base.compareTo(quote) < 0 ? base + "/" + quote : quote + "/" + base;
    }
}
