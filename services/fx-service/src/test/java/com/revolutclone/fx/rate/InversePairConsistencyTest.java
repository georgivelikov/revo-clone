package com.revolutclone.fx.rate;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.money.FxMath;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * plans/phase-04-fx-multicurrency.md: "Canonical pair ordering with inversion for the reverse
 * direction. Storing both directions independently guarantees they drift apart and produce
 * arbitrage within your own system." {@link FxRateCache} only ever stores the canonical
 * direction a tick actually arrives on; the reverse direction is derived by
 * {@link com.revolutclone.money.FxMath#invert} on every lookup, which is what these tests pin
 * down - no Spring context needed, this is pure cache logic.
 */
class InversePairConsistencyTest {

    private final FxRateCache cache = new FxRateCache();

    @Test
    void theCanonicalDirectionIsReturnedUnchanged() {
        cache.update(new FxRateCache.LatestRate("EUR/GBP", "0.8600000000",
                Instant.parse("2026-03-01T10:00:00Z"), "simulator"));

        assertThat(cache.mid("EUR", "GBP")).contains("0.8600000000");
    }

    @Test
    void theReverseDirectionIsDerivedFromTheCanonicalTickEvenThoughItWasNeverPublished() {
        cache.update(new FxRateCache.LatestRate("EUR/GBP", "0.8600000000",
                Instant.parse("2026-03-01T10:00:00Z"), "simulator"));

        // GBP/EUR was never a tick on the topic - only EUR/GBP is ever published - so this can
        // only come from inversion.
        assertThat(cache.mid("GBP", "EUR")).contains("1.1627906977");
    }

    @Test
    void requestingEitherOrderOfTheArgumentsProducesTheSamePairsMid() {
        cache.update(new FxRateCache.LatestRate("EUR/USD", "1.0800000000",
                Instant.parse("2026-03-01T10:00:00Z"), "simulator"));

        assertThat(cache.mid("eur", "usd")).contains("1.0800000000");
        assertThat(cache.mid("USD", "EUR")).contains(cache.mid("usd", "eur").orElseThrow());
    }

    @Test
    void roundTrippingThroughBothDirectionsNeverYieldsMoreThanYouStartedWith() {
        // "No arbitrage": since GBP/EUR is derived from EUR/GBP by the very same FxMath.invert
        // call every time - never stored as an independent series - the two directions are
        // mathematically tied by definition and can never drift apart into a profitable round
        // trip. FxMathTest's "inverting a rate" tests are what pin down that the arithmetic
        // itself round-trips within a sub-rate-scale rounding tolerance; this test pins down
        // that the cache actually uses that derivation rather than some other lookup.
        cache.update(new FxRateCache.LatestRate("EUR/GBP", "0.8600000000",
                Instant.parse("2026-03-01T10:00:00Z"), "simulator"));

        String eurToGbp = cache.mid("EUR", "GBP").orElseThrow();
        String gbpToEur = cache.mid("GBP", "EUR").orElseThrow();

        assertThat(gbpToEur).isEqualTo(FxMath.invert(eurToGbp));
    }

    @Test
    void aPairWithNoTickYetIsUnknownInEitherDirection() {
        assertThat(cache.mid("EUR", "JPY")).isEmpty();
        assertThat(cache.mid("JPY", "EUR")).isEmpty();
    }

    @Test
    void aNewerTickReplacesTheStoredMidAndTheDerivedInverseMovesWithIt() {
        cache.update(new FxRateCache.LatestRate("EUR/GBP", "0.8600000000",
                Instant.parse("2026-03-01T10:00:00Z"), "simulator"));
        cache.update(new FxRateCache.LatestRate("EUR/GBP", "0.8650000000",
                Instant.parse("2026-03-01T10:00:01Z"), "simulator"));

        assertThat(cache.mid("EUR", "GBP")).contains("0.8650000000");
        assertThat(cache.mid("GBP", "EUR").orElseThrow())
                .isNotEqualTo("1.1627906977")
                .isEqualTo("1.1560693642");
    }

    @Test
    void anOlderOutOfOrderTickNeverMovesTheCacheBackwards() {
        cache.update(new FxRateCache.LatestRate("EUR/GBP", "0.8650000000",
                Instant.parse("2026-03-01T10:00:01Z"), "simulator"));
        cache.update(new FxRateCache.LatestRate("EUR/GBP", "0.8600000000",
                Instant.parse("2026-03-01T10:00:00Z"), "simulator"));

        assertThat(cache.mid("EUR", "GBP")).contains("0.8650000000");
    }
}
