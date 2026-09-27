package com.revolutclone.simulator.fx;

import com.revolutclone.money.FxMath;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * A geometric random walk on a mid-rate: delegates the actual decimal arithmetic to
 * {@link FxMath#randomWalkStep}, the one place an arbitrary-precision decimal type is allowed
 * (CLAUDE.md hard rule 1) - this class only ever holds a rate or a volatility as its
 * decimal-string text, never as a parsed number.
 *
 * <p>A random walk (rather than a static table) is deliberate per the phase's Design detail: it
 * makes quote expiry meaningful and makes "the rate moved between quote and confirm" reachable
 * in testing.
 */
public class RandomWalkRateGenerator {

    private final RandomGenerator random;
    private final String volatility;

    public RandomWalkRateGenerator(RandomGenerator random, String volatility) {
        this.random = Objects.requireNonNull(random, "random");
        this.volatility = Objects.requireNonNull(volatility, "volatility");
    }

    /** One step of the walk from {@code currentMid}. */
    public String next(String currentMid) {
        Objects.requireNonNull(currentMid, "currentMid");
        String noiseSample = Double.toString(random.nextGaussian());
        return FxMath.randomWalkStep(currentMid, volatility, noiseSample);
    }
}
