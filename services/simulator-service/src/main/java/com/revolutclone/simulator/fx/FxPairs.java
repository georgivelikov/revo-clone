package com.revolutclone.simulator.fx;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The four pairs plans/phase-04-fx-multicurrency.md names, canonically ordered (alphabetical
 * ISO code - {@code "EUR/GBP"}, never {@code "GBP/EUR"}) with a plausible seed mid to start each
 * walk from. Seeds are decimal strings, not a parsed arbitrary-precision decimal type - see
 * {@link RandomWalkRateGenerator} and CLAUDE.md hard rule 1. EUR/JPY is here specifically
 * because JPY has zero minor digits and will catch every later place that assumes two - though
 * that only bites once {@code fx-service} converts an amount, not in this rate feed itself.
 */
public final class FxPairs {

    public static final Map<String, String> SEED_MID = Map.copyOf(seedRates());

    private FxPairs() {
    }

    private static Map<String, String> seedRates() {
        Map<String, String> seeds = new LinkedHashMap<>();
        seeds.put("EUR/GBP", "0.86000000");
        seeds.put("EUR/USD", "1.08000000");
        seeds.put("GBP/USD", "1.26000000");
        seeds.put("EUR/JPY", "162.50000000");
        return seeds;
    }
}
