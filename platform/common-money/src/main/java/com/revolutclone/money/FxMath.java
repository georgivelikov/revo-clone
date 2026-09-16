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
}
