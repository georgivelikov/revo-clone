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

    /** Basis points per unit: 50 bps is 0.5%. */
    private static final int BPS_PER_UNIT = 10_000;

    private FxMath() {
    }

    /**
     * A conversion and the part of it that would not fit: {@code converted} is the whole minor
     * units a ledger can actually post, and {@code residualMinorFraction} is the signed fraction
     * of one minor unit that {@link RoundingMode#HALF_EVEN} discarded to get there, as exact
     * decimal text. The two always reconstruct the unrounded product - see
     * {@link #exactMinorUnits()} - which is the point of returning them together.
     *
     * <p>Sign convention: {@code residual = exact - converted}. A tie rounded *up* leaves a
     * negative residual (the target side was credited marginally more than the rate strictly
     * justified), a tie rounded *down* a positive one. {@code |residual| < 1} always, and at a
     * tie exactly {@code 0.5}.
     *
     * <p>plans/phase-04-fx-multicurrency.md needs this because an FX journal entry rounds three
     * legs on the buy side independently - the pool leg at the mid rate, the user leg at the
     * client rate, the fee leg being the spread between them - and three sub-unit roundings need
     * not cancel. What is left over is posted explicitly to {@code FX_PNL} as a sixth leg. The
     * residual is what makes that leg a computed figure rather than a plug: "{@code FxMath}
     * returns both the converted amount and the residual so this is not a guess."
     *
     * <p>The residual is decimal text rather than a number for the same reason every other rate
     * in this codebase is: CLAUDE.md's hard rule 1 confines {@link BigDecimal} to this class, and
     * a sub-unit fraction has no faithful representation as a {@code long}. Arithmetic on it
     * therefore belongs on this type, not at the call site.
     */
    public record FxConversion(Money converted, String residualMinorFraction) {

        public FxConversion {
            Objects.requireNonNull(converted, "converted");
            Objects.requireNonNull(residualMinorFraction, "residualMinorFraction");
        }

        /** True when the rate divided into the amount exactly and nothing was discarded. */
        public boolean isExact() {
            return new BigDecimal(residualMinorFraction).signum() == 0;
        }

        /**
         * The unrounded product, in minor units of the target currency, reconstructed as
         * {@code converted + residual}. Nothing a conversion touches is unaccounted for: this is
         * the identity {@code FxConversionRoundingTest} asserts against the arithmetic done by
         * hand.
         */
        public String exactMinorUnits() {
            return new BigDecimal(converted.minor())
                    .add(new BigDecimal(residualMinorFraction))
                    .stripTrailingZeros()
                    .toPlainString();
        }
    }

    /**
     * Converts {@code from} into {@code toCurrency} at {@code rate}, scaling to the target
     * currency's fraction digits with {@link RoundingMode#HALF_EVEN} - the rounding mode with no
     * systematic bias, so ties cancel out over many conversions instead of accumulating in
     * either party's favour.
     *
     * <p>Discards the rounding residual. Use {@link #convertWithResidual} wherever the residual
     * has to be posted rather than dropped, which is anywhere the result lands in the ledger.
     */
    public static Money convert(Money from, BigDecimal rate, String toCurrency) {
        Objects.requireNonNull(rate, "rate");
        return convertWithResidual(from, rate.toPlainString(), toCurrency).converted();
    }

    /**
     * {@link #convert} plus the sub-unit residual it would otherwise throw away - see
     * {@link FxConversion}. This is the form the FX posting needs, because a rounding residual
     * that is silently dropped is money created or destroyed, and the ledger's per-currency
     * zero-sum invariant notices.
     *
     * <p>Takes the rate as exact decimal text (as {@code fx-service}'s rate cache holds it)
     * rather than a {@link BigDecimal}, so callers never have to name that type; see CLAUDE.md
     * hard rule 1.
     *
     * @throws NumberFormatException    if {@code rate} is not a valid decimal number
     * @throws IllegalArgumentException if {@code rate} is not positive, or {@code toCurrency} is
     *                                  not a known ISO currency
     */
    public static FxConversion convertWithResidual(Money from, String rate, String toCurrency) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(toCurrency, "toCurrency");

        BigDecimal parsedRate = new BigDecimal(rate);
        if (parsedRate.signum() <= 0) {
            throw new IllegalArgumentException("FX rate must be positive: " + parsedRate.toPlainString());
        }

        Currency target = Currency.getInstance(toCurrency);

        // Work in the target's minor units throughout: a currency with zero fraction digits (JPY)
        // then needs no special case, and the residual comes out denominated in the same unit the
        // posting is, rather than in major units the ledger never sees.
        BigDecimal exactMinor = from.toMajor()
                .multiply(parsedRate)
                .movePointRight(target.getDefaultFractionDigits());
        BigDecimal roundedMinor = exactMinor.setScale(0, RoundingMode.HALF_EVEN);

        Money converted = new Money(roundedMinor.longValueExact(), target);
        String residual = exactMinor.subtract(roundedMinor).stripTrailingZeros().toPlainString();
        return new FxConversion(converted, residual);
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
     * The rate a client is actually given: the mid rate less {@code spreadBps} basis points of
     * itself, rounded to {@link #RATE_SCALE} so the result is exactly what the
     * {@code numeric(20,10)} column will hold and nothing is lost between quoting and persisting.
     *
     * <p>Always applied in the direction the client sells, so the spread is always in the house's
     * favour whichever way round the pair is asked for - a rate below mid means fewer units of the
     * buy currency for the same sell amount. That single-sided application is what stops
     * plans/phase-04-fx-multicurrency.md's inverse-pair trap: "`1/0.8543` is not exactly the
     * GBP/EUR rate once spread is applied in both directions. Derive one direction from the other
     * and apply spread once."
     *
     * <p>Pair this with {@link #spreadRate}, not with an independently computed fee: the two
     * together are exactly the mid rate, which is what lets the FX entry's three buy-side legs
     * (pool at the mid rate, client at this rate, the difference as fee revenue) reconstruct the
     * mid amount with only a sub-unit residual left over.
     *
     * @throws IllegalArgumentException if {@code mid} is not positive, or {@code spreadBps} is
     *                                  negative or would wipe out the whole rate
     */
    public static String applySpread(String mid, int spreadBps) {
        Objects.requireNonNull(mid, "mid");
        BigDecimal midRate = new BigDecimal(mid);
        if (midRate.signum() <= 0) {
            throw new IllegalArgumentException("mid rate must be positive: " + mid);
        }
        if (spreadBps < 0 || spreadBps >= BPS_PER_UNIT) {
            throw new IllegalArgumentException(
                    "spreadBps must be in [0, " + BPS_PER_UNIT + "), got " + spreadBps);
        }
        BigDecimal keptFraction = BigDecimal.ONE
                .subtract(BigDecimal.valueOf(spreadBps).movePointLeft(4));
        return midRate.multiply(keptFraction).setScale(RATE_SCALE, RoundingMode.HALF_EVEN).toPlainString();
    }

    /**
     * The per-unit fee the spread is worth: {@code mid - clientRate}, as exact decimal text.
     *
     * <p>Deliberately a subtraction of the two rates that are actually used rather than a second,
     * independent {@code mid * bps} calculation. {@link #applySpread} rounds the client rate to the
     * scale the database stores, so a separately derived fee rate would not add back up to the mid
     * rate and the FX entry's fee leg would silently absorb that discrepancy - which is the exact
     * failure mode plans/phase-04-fx-multicurrency.md warns about, only in reverse
     * ({@code FEE_INCOME} carrying drift rather than the pool). Subtracting guarantees
     * {@code clientRate + spreadRate == mid} exactly, so the only thing the fee leg can be off by
     * is the sub-unit rounding the {@code FX_PNL} leg is there to hold.
     *
     * @throws IllegalArgumentException if {@code clientRate} exceeds {@code mid} - a negative
     *                                  spread would mean paying the client for the privilege
     */
    public static String spreadRate(String mid, String clientRate) {
        Objects.requireNonNull(mid, "mid");
        Objects.requireNonNull(clientRate, "clientRate");
        BigDecimal midRate = new BigDecimal(mid);
        BigDecimal client = new BigDecimal(clientRate);
        if (client.compareTo(midRate) > 0) {
            throw new IllegalArgumentException(
                    "client rate " + clientRate + " is better than the mid rate " + mid);
        }
        return midRate.subtract(client).setScale(RATE_SCALE, RoundingMode.HALF_EVEN).toPlainString();
    }

    /**
     * A basis-point charge on an amount, in that amount's own currency, rounded to whole minor
     * units with {@link RoundingMode#HALF_EVEN}.
     *
     * <p>Lives here rather than on {@link Money} because the only charge in this project shaped
     * like this is the <strong>card mark-up</strong> on a foreign-currency authorization
     * (plans/phase-05-cards.md step 4: "fx-service rate, mark up"), which exists solely because a
     * conversion happened. It is deliberately <em>not</em> a second spread folded into the rate:
     *
     * <ul>
     *   <li>The exchange spread is a rate adjustment and belongs to {@code fx-service}, which owns
     *       the rate and books the spread as its own revenue - see {@link #applySpread}.</li>
     *   <li>The card mark-up is a percentage of the <em>converted</em> amount and belongs to
     *       {@code card-service}. Applied this way it is an exact integer addend: converted plus
     *       mark-up is the amount held, with no sub-unit residual anywhere between them, so the
     *       settlement entry can credit the mark-up to {@code FEE_INCOME} and the rest to
     *       {@code card-settlement} and still sum to zero without a plug leg.</li>
     * </ul>
     *
     * <p>That exactness is the whole reason for the shape. Marking the <em>rate</em> up a second
     * time would make the mark-up the difference between two independently rounded conversions,
     * and {@code FEE_INCOME} would then answer "the mark-up, plus or minus rounding noise" -
     * precisely the failure {@code fx-service}'s three-way leg rating exists to avoid on the
     * exchange side.
     *
     * @throws IllegalArgumentException if {@code bps} is negative or at least one whole unit
     */
    public static Money bpsOf(Money amount, int bps) {
        Objects.requireNonNull(amount, "amount");
        if (bps < 0 || bps >= BPS_PER_UNIT) {
            throw new IllegalArgumentException("bps must be in [0, " + BPS_PER_UNIT + "), got " + bps);
        }
        BigDecimal charge = new BigDecimal(amount.minor())
                .multiply(BigDecimal.valueOf(bps).movePointLeft(4))
                .setScale(0, RoundingMode.HALF_EVEN);
        return new Money(charge.longValueExact(), amount.currency());
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
