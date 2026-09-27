package com.revolutclone.card.authz;

/**
 * Step 4 of plans/phase-05-cards.md's chain - "FX if merchant currency ≠ wallet" - is task 05.5's
 * work, and this is the seam until it lands.
 *
 * <p>A failure and not a decline, for the same reason as {@link FundingWalletUnknownException}: the
 * card is fine, the holder is fine, and the network would treat a decline as this card's final answer
 * for a purchase it should have covered. Nothing publishes a foreign-currency authorization yet - the
 * merchant simulator arrives in task 05.8, after the FX path - so in practice this marks the gap
 * rather than being reached.
 */
public class ForeignCurrencyAuthorizationUnsupportedException extends RuntimeException {

    public ForeignCurrencyAuthorizationUnsupportedException(String merchantCurrency, String walletCurrency) {
        super("merchant charged in " + merchantCurrency + " against a " + walletCurrency
                + " wallet; the card FX path is task 05.5");
    }
}
