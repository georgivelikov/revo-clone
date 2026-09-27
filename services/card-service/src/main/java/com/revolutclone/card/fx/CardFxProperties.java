package com.revolutclone.card.fx;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where fx-service lives, and the one number card-service is allowed to have an opinion about on a
 * foreign-currency purchase.
 *
 * <p><strong>{@code markUpBps} is card-service's, and the exchange spread is fx-service's.</strong>
 * That split is the substance of plans/phase-05-cards.md task 5 - "the card mark-up applied
 * separately from the exchange spread" - and making it two properties in two services is what stops
 * it collapsing back into one number. They are different charges for different reasons: the spread
 * is the price of the exchange itself and is booked by fx-service, while the mark-up is what the
 * card programme charges for settling a purchase in a currency the wallet does not hold. A holder
 * who reads "you spent $50, we charged you EUR 40.60" is entitled to have the two explained apart,
 * and an operator turning one to zero must be able to do it without touching the other.
 *
 * <p>Defaults to 100 bps (1%), which is the order of magnitude a real card programme charges
 * out-of-hours. Zero is a legitimate setting - "no mark-up today" - and produces a billing amount
 * equal to the exchange with no fee to credit, rather than a broken one.
 */
@ConfigurationProperties(prefix = "revolut.card.fx")
public record CardFxProperties(String baseUrl, int markUpBps) {

    public CardFxProperties {
        if (markUpBps < 0 || markUpBps >= 10_000) {
            throw new IllegalArgumentException(
                    "revolut.card.fx.mark-up-bps must be in [0, 10000), got " + markUpBps);
        }
    }
}
