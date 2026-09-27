package com.revolutclone.simulator.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.Test;

class RandomWalkRateGeneratorTest {

    @Test
    void walksAwayFromTheSeedOverManySteps() {
        RandomWalkRateGenerator generator = new RandomWalkRateGenerator(new Random(42), "0.0005");
        String mid = "0.86000000";

        for (int i = 0; i < 200; i++) {
            mid = generator.next(mid);
        }

        assertThat(mid).isNotEqualTo("0.86000000");
        assertThat(mid).doesNotStartWith("-");
    }

    @Test
    void neverProducesZeroOrNegative() {
        // A large "volatility" would otherwise drive the mid arbitrarily far down; a fixed seed
        // makes this deterministic rather than a flaky property test.
        RandomWalkRateGenerator generator = new RandomWalkRateGenerator(new Random(7), "50");
        String mid = "0.86000000";

        for (int i = 0; i < 500; i++) {
            mid = generator.next(mid);
            assertThat(mid).doesNotStartWith("-");
        }
    }

    @Test
    void sameSeedProducesTheSameWalk() {
        RandomWalkRateGenerator first = new RandomWalkRateGenerator(new Random(123), "0.0005");
        RandomWalkRateGenerator second = new RandomWalkRateGenerator(new Random(123), "0.0005");

        assertThat(first.next("1.08000000")).isEqualTo(second.next("1.08000000"));
    }

    @Test
    void rejectsNegativeVolatility() {
        RandomWalkRateGenerator generator = new RandomWalkRateGenerator(new Random(), "-0.1");

        assertThatThrownBy(() -> generator.next("1.00000000")).isInstanceOf(IllegalArgumentException.class);
    }
}
