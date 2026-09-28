package com.revolutclone.simulator.card;

import java.util.random.RandomGenerator;

/**
 * One row of {@link MerchantCatalogue}. {@code mcc} and {@code country} are fixed-width to match
 * {@code card_authorization.merchant_mcc char(4)} / {@code merchant_country char(2)} exactly -
 * plans/phase-05-cards.md's schema.
 */
public record Merchant(String name, String mcc, String country, String currency, long minAmountMinor,
        long maxAmountMinor) {

    public Merchant {
        if (mcc.length() != 4) {
            throw new IllegalArgumentException("mcc must be 4 characters: " + mcc);
        }
        if (country.length() != 2) {
            throw new IllegalArgumentException("country must be 2 characters: " + country);
        }
        if (minAmountMinor <= 0 || maxAmountMinor < minAmountMinor) {
            throw new IllegalArgumentException(
                    "amount range must be positive and non-empty: [" + minAmountMinor + ", " + maxAmountMinor + "]");
        }
    }

    /** A plausible amount for a purchase at this merchant, uniformly across its range. */
    public long randomAmountMinor(RandomGenerator random) {
        return minAmountMinor + random.nextLong(maxAmountMinor - minAmountMinor + 1);
    }
}
