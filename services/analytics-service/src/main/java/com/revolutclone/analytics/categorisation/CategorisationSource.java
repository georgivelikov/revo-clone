package com.revolutclone.analytics.categorisation;

/**
 * {@code transaction_category.source} - which of the three resolution layers won, or
 * {@link #DEFAULT} when none of them matched anything and the transaction fell through to
 * {@link Category#OTHER}. Matches the phase's own {@code USER | MERCHANT_RULE | MCC | DEFAULT}
 * exactly.
 */
public final class CategorisationSource {

    public static final String USER = "USER";
    public static final String MERCHANT_RULE = "MERCHANT_RULE";
    public static final String MCC = "MCC";
    public static final String DEFAULT = "DEFAULT";

    private CategorisationSource() {
    }
}
