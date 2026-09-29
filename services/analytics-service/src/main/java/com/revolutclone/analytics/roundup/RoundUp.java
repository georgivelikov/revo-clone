package com.revolutclone.analytics.roundup;

import java.util.Currency;

/**
 * The round-up formula itself (task 06.7), entirely in integer minor-unit arithmetic - CLAUDE.md
 * hard rule 1 forbids {@code double}/{@code float} for money, and {@link java.math.BigDecimal} is
 * reserved for {@code common-money}'s {@code Money}/{@code FxMath} doorways only, so this class
 * uses neither.
 *
 * <p>plans/phase-06-vaults-analytics.md's own worked example: {@code spend 4.37 EUR -> round to
 * 5.00 -> transfer 0.63 EUR}. {@code fractionDigits} comes from
 * {@link Currency#getDefaultFractionDigits()} - the same derivation {@code common-money}'s
 * {@code Money.toMajor()} uses internally - never a hardcoded 100, so a zero-decimal currency
 * (JPY) rounds to the nearest whole unit rather than the nearest "cent" of one.
 */
public final class RoundUp {

    private RoundUp() {
    }

    /**
     * The next whole major unit above {@code settledMinor}, minus {@code settledMinor}.
     *
     * @return zero, not the whole unit, when {@code settledMinor} already sits exactly on a
     *         whole-unit boundary (RoundUpTest's own "5.00 -> zero round-up" case) or when
     *         {@code settledMinor} is not positive - there is nothing to round up on a refund or a
     *         zero-amount settlement.
     */
    public static long compute(long settledMinor, String currencyCode) {
        if (settledMinor <= 0) {
            return 0L;
        }
        int fractionDigits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
        long unit = 1L;
        for (int i = 0; i < fractionDigits; i++) {
            unit *= 10L;
        }
        long remainder = settledMinor % unit;
        return remainder == 0 ? 0L : unit - remainder;
    }
}
