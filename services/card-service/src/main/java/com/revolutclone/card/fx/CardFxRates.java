package com.revolutclone.card.fx;

/**
 * The port step 4 of the authorization chain reads a rate through. A port and not a direct
 * {@code RestClient} call for the same reason {@code LedgerReservations} is one: the decision chain
 * is the thing under test, and a test of it should not need a second service running to answer a
 * question about arithmetic.
 */
public interface CardFxRates {

    /**
     * The rate to charge a {@code merchantCurrency} purchase against a {@code walletCurrency}
     * wallet, mid and spread-adjusted.
     *
     * @throws CardFxRateUnavailableException if fx-service cannot be reached or knows no rate for
     *                                       the pair. Never a decline - see that class
     */
    CardFxRate rate(String walletCurrency, String merchantCurrency);
}
