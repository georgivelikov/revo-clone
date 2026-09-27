package com.revolutclone.simulator.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.contracts.events.FxRateTickV1;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FxRateTickServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    private final FxRateTickService service = new FxRateTickService(
            new RandomWalkRateGenerator(new Random(1), "0.0005"), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

    @Test
    void producesOneTickPerSupportedPairEveryCall() {
        List<FxRateTickV1> ticks = service.tick();

        assertThat(ticks).hasSize(FxPairs.SEED_MID.size());
        assertThat(ticks.stream().map(FxRateTickV1::pair)).containsExactlyInAnyOrderElementsOf(
                FxPairs.SEED_MID.keySet());
    }

    @Test
    void everyPairIsAlphabeticallyOrdered() {
        Set<String> pairs = FxPairs.SEED_MID.keySet();

        for (String pair : pairs) {
            String[] parts = pair.split("/");
            assertThat(parts).hasSize(2);
            assertThat(parts[0]).isLessThan(parts[1]);
        }
    }

    @Test
    void stampsEveryTickWithTheServiceClockAndSource() {
        List<FxRateTickV1> ticks = service.tick();

        assertThat(ticks).allSatisfy(tick -> {
            assertThat(tick.asOf()).isEqualTo(FIXED_INSTANT);
            assertThat(tick.source()).isEqualTo("simulator-service");
        });
    }

    @Test
    void eachCallWalksOnFromThePreviousMidRatherThanResetting() {
        List<FxRateTickV1> first = service.tick();
        List<FxRateTickV1> second = service.tick();

        String firstEurGbp = midOf(first, "EUR/GBP");
        String secondEurGbp = midOf(second, "EUR/GBP");

        assertThat(secondEurGbp).isNotEqualTo(firstEurGbp);
    }

    private static String midOf(List<FxRateTickV1> ticks, String pair) {
        return ticks.stream()
                .filter(tick -> tick.pair().equals(pair))
                .findFirst()
                .orElseThrow()
                .mid();
    }
}
