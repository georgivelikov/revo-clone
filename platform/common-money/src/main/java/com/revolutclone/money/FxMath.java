package com.revolutclone.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * The one place in the project a foreign-exchange rate is applied. Everywhere else, an amount
 * only ever moves within a single currency - see {@link Money}.
 */
public final class FxMath {

    private static final int RANDOM_WALK_SCALE = 8;
    private static final BigDecimal RANDOM_WALK_FLOOR = new BigDecimal("0.00010000");

    /** Matches the {@code numeric(20,10)} column every rate is ultimately persisted in. */
    private static final int RATE_SCALE = 10;

    private FxMath() {
    }

    /**
     * Converts {@code from} into {@code toCurrency} at {@code rate}, scaling to the target
     * currency's fraction digits with {@link RoundingMode#HALF_EVEN} - the rounding mode with no
     * systematic bias, so ties cancel out over many conversions instead of accumulating in
     * either party's favour.
     */
    public static Money convert(Money from, BigDecimal rate, String toCurrency) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(toCurrency, "toCurrency");
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("FX rate must be positive: " + rate.toPlainString());
        }

        Currency target = Currency.getInstance(toCurrency);
        int fractionDigits = target.getDefaultFractionDigits();

        BigDecimal convertedMajor = from.toMajor()
                .multiply(rate)
                .setScale(fractionDigits, RoundingMode.HALF_EVEN);

        long minor = convertedMajor.unscaledValue().longValueExact();
        return new Money(minor, target);
    }

    /**
     * One step of a geometric random walk on a mid-rate: {@code next = current * (1 + noise)},
     * where {@code noise} is a pre-drawn Gaussian sample scaled by {@code volatility}. Backs
     * {@code simulator-service}'s FX rate feed (plans/phase-04-fx-multicurrency.md), which needs
     * a moving mid rather than a static table so quote expiry is meaningful.
     *
     * <p>Every argument and the result are decimal strings, not {@link BigDecimal} - CLAUDE.md's
     * hard rule 1 confines {@code BigDecimal} to this class and {@link Money#toMajor()}, so
     * every other class in the codebase, including this method's caller, holds a rate as its
     * exact decimal text and never declares the type itself. The Gaussian sample is generated
     * by the caller (a {@code java.util.Random} is not FX arithmetic) and passed in as a string
     * for the same reason.
     *
     * <p>Never returns zero or negative - clamped to a small positive floor instead, since a
     * rate that direction would make every downstream conversion nonsensical.
     */
    public static String randomWalkStep(String currentMid, String volatility, String gaussianNoiseSample) {
        Objects.requireNonNull(currentMid, "currentMid");
        Objects.requireNonNull(volatility, "volatility");
        Objects.requireNonNull(gaussianNoiseSample, "gaussianNoiseSample");

        BigDecimal mid = new BigDecimal(currentMid);
        BigDecimal vol = new BigDecimal(volatility);
        if (vol.signum() < 0) {
            throw new IllegalArgumentException("volatility must not be negative: " + volatility);
        }

        BigDecimal noise = new BigDecimal(gaussianNoiseSample).multiply(vol);
        BigDecimal next = mid.add(mid.multiply(noise)).setScale(RANDOM_WALK_SCALE, RoundingMode.HALF_EVEN);
        return (next.compareTo(RANDOM_WALK_FLOOR) < 0 ? RANDOM_WALK_FLOOR : next).toPlainString();
    }

    /**
     * Derives the reverse direction of a canonically-ordered pair, e.g. {@code GBP/EUR} from a
     * stored {@code EUR/GBP} mid. plans/phase-04-fx-multicurrency.md's rate feed design is
     * explicit that this must be *derived*, never stored as a second independent series: "Storing
     * both directions independently guarantees they drift apart and produce arbitrage within your
     * own system." {@code fx-service}'s rate cache calls this on every reverse-direction lookup
     * rather than caching the result, so the two directions can never disagree with each other.
     *
     * <p>Decimal text in and out, like {@link #randomWalkStep} - CLAUDE.md's hard rule 1 confines
     * {@link BigDecimal} to this class.
     */
    public static String invert(String mid) {
        Objects.requireNonNull(mid, "mid");
        BigDecimal value = new BigDecimal(mid);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("rate must be positive: " + mid);
        }
        return BigDecimal.ONE.divide(value, RATE_SCALE, RoundingMode.HALF_EVEN).toPlainString();
    }
}
