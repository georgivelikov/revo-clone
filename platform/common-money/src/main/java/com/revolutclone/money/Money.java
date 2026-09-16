package com.revolutclone.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Currency;
import java.util.Objects;

/**
 * An exact amount of money: a count of the currency's smallest unit ({@code minor}) plus the
 * ISO currency itself. There is no {@code double} or {@code float} anywhere in this class, and
 * {@link BigDecimal} appears only at the two doorways to decimal notation - {@link #ofMajor}
 * and {@link #toMajor} - because a rounding error at either of those is a real cent gained or
 * lost, and everywhere else in the codebase talks in minor units precisely to avoid that.
 */
public record Money(long minor, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException(
                    currency.getCurrencyCode() + " has no defined minor unit and cannot be represented as Money");
        }
    }

    public static Money of(long minor, String currencyCode) {
        return new Money(minor, parseCurrency(currencyCode));
    }

    /** Exact: throws rather than rounding when {@code major} carries more scale than the currency allows. */
    public static Money ofMajor(BigDecimal major, String currencyCode) {
        Objects.requireNonNull(major, "major");
        Currency currency = parseCurrency(currencyCode);
        int fractionDigits = currency.getDefaultFractionDigits();

        BigDecimal scaled;
        try {
            scaled = major.setScale(fractionDigits, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new ArithmeticException("%s is not exactly representable in %s (%d minor-unit digit(s))"
                    .formatted(major.toPlainString(), currency.getCurrencyCode(), fractionDigits));
        }
        long minor = scaled.unscaledValue().longValueExact();
        return new Money(minor, currency);
    }

    public static Money zero(String currencyCode) {
        return of(0, currencyCode);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minor), currency);
    }

    public Money abs() {
        return new Money(Math.absExact(minor), currency);
    }

    public boolean isZero() {
        return minor == 0;
    }

    public boolean isPositive() {
        return minor > 0;
    }

    public boolean isNegative() {
        return minor < 0;
    }

    /**
     * Splits this amount across {@code ratios} using the largest-remainder method: each share
     * starts at its truncated proportion, then the units left over by truncation are handed out
     * one at a time to the shares with the largest fractional remainder, earliest index first on
     * a tie. This is the one place a naive {@code amount / ratios.length} would lose units.
     */
    public Money[] allocate(int... ratios) {
        Objects.requireNonNull(ratios, "ratios");
        if (ratios.length == 0) {
            throw new IllegalArgumentException("allocate requires at least one ratio");
        }
        long sum = 0;
        for (int ratio : ratios) {
            if (ratio < 0) {
                throw new IllegalArgumentException("allocation ratios must not be negative: " + ratio);
            }
            sum += ratio;
        }
        if (sum == 0) {
            throw new IllegalArgumentException("allocation ratios must sum to more than zero");
        }

        long absMinor = Math.absExact(minor);
        int sign = Long.signum(minor);

        long[] base = new long[ratios.length];
        long[] remainder = new long[ratios.length];
        long totalBase = 0;
        for (int i = 0; i < ratios.length; i++) {
            long numerator = Math.multiplyExact(absMinor, (long) ratios[i]);
            base[i] = numerator / sum;
            remainder[i] = numerator % sum;
            totalBase += base[i];
        }
        long leftover = absMinor - totalBase;

        Integer[] byRemainderDescending = new Integer[ratios.length];
        for (int i = 0; i < byRemainderDescending.length; i++) {
            byRemainderDescending[i] = i;
        }
        Arrays.sort(byRemainderDescending, (a, b) -> {
            int cmp = Long.compare(remainder[b], remainder[a]);
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });
        for (int k = 0; k < leftover; k++) {
            base[byRemainderDescending[k]]++;
        }

        Money[] parts = new Money[ratios.length];
        for (int i = 0; i < ratios.length; i++) {
            parts[i] = new Money(sign * base[i], currency);
        }
        return parts;
    }

    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minor, currency.getDefaultFractionDigits());
    }

    @Override
    public String toString() {
        return toMajor().toPlainString() + " " + currency.getCurrencyCode();
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minor, other.minor);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    private static Currency parseCurrency(String currencyCode) {
        Objects.requireNonNull(currencyCode, "currencyCode");
        return Currency.getInstance(currencyCode);
    }
}
