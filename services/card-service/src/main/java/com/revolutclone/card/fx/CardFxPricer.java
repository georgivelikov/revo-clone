package com.revolutclone.card.fx;

import com.revolutclone.money.FxMath;
import com.revolutclone.money.Money;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Step 4 of plans/phase-05-cards.md's authorization flow: "FX if merchant currency != wallet ->
 * fx-service rate, mark up".
 *
 * <p><strong>The conversion runs in the direction the holder sells.</strong> A purchase fixes the
 * merchant's side - the merchant asks for exactly $50.00 and will not take $49.99 - so the wallet
 * supplies whatever that costs. fx-service is therefore asked for the wallet-to-merchant pair
 * (merchant units per wallet unit), applies its spread in that direction, and this converts by
 * inverting the result. The order matters and is the phase's inverse-pair trap seen from the card
 * side: {@code FxMath.applySpread} always moves a rate in the house's favour for the currency being
 * sold, so inverting first and applying the spread afterwards would quietly hand the spread to the
 * holder. {@code QuoteService} derives a sell amount from a client-supplied buy amount exactly this
 * way, for exactly this reason.
 *
 * <p><strong>Then the mark-up, on the converted amount and not on the rate.</strong> See
 * {@link CardFxProperties} for why the two charges are separate, and {@link CardFxPricing} for why
 * applying the mark-up to whole minor units is what keeps the split exact enough to post.
 *
 * <p><strong>The rounding residual is discarded here, and that is correct.</strong> An FX journal
 * entry has to post its residual to {@code FX_PNL} because it has two sides in two currencies and
 * both must net to zero. This conversion has one side: the wallet is charged whole minor units of its
 * own currency, and no account in this ledger is ever denominated in the merchant's currency for
 * this purchase - the scheme pays the merchant, not us. There is no counter-leg for a sub-unit
 * fraction to fail to balance against, so there is nothing to place.
 *
 * <p><strong>No conversion is not a special case.</strong> A merchant already charging in the
 * wallet's currency yields {@link CardFxPricing#domestic}, so the decision chain has one path, the
 * limit check always reads a billing amount in the wallet's currency, and the hold is always placed
 * in the currency the wallet is denominated in.
 */
@Component
public class CardFxPricer {

    private static final Logger log = LoggerFactory.getLogger(CardFxPricer.class);

    private final CardFxRates rates;
    private final CardFxProperties properties;

    public CardFxPricer(CardFxRates rates, CardFxProperties properties) {
        this.rates = rates;
        this.properties = properties;
    }

    /**
     * Prices one tap against one wallet.
     *
     * @param merchantAmount what the merchant asked for, in the merchant's currency
     * @param walletCurrency the funding wallet's currency - the currency the hold, the limits and
     *                       every later posting are denominated in
     * @throws CardFxRateUnavailableException if a conversion is needed and no rate can be had. Not a
     *                                       decline - see that class
     */
    public CardFxPricing price(Money merchantAmount, String walletCurrency) {
        String wallet = walletCurrency.toUpperCase(Locale.ROOT);
        if (merchantAmount.currency().getCurrencyCode().equals(wallet)) {
            return CardFxPricing.domestic(merchantAmount);
        }

        String merchant = merchantAmount.currency().getCurrencyCode();
        CardFxRate rate = rates.rate(wallet, merchant);

        Money exchanged = FxMath
                .convertWithResidual(merchantAmount, FxMath.invert(rate.exchangeRate()), wallet)
                .converted();
        if (!exchanged.isPositive()) {
            // A purchase worth less than one minor unit of the wallet's currency. Nothing to hold and
            // nothing sensible to charge; a rate this far out is a broken feed, not a small purchase.
            throw new CardFxRateUnavailableException(merchantAmount + " converts to " + exchanged
                    + " at " + rate.exchangeRate() + " " + merchant + " per " + wallet);
        }

        int markUpBps = properties.markUpBps();
        Money markUp = FxMath.bpsOf(exchanged, markUpBps);
        Money billing = exchanged.plus(markUp);

        log.debug("priced a {} purchase against a {} wallet at {} ({} bps spread): {} plus {} mark-up "
                        + "({} bps) = {}", merchant, wallet, rate.exchangeRate(), rate.spreadBps(),
                exchanged, markUp, markUpBps, billing);

        return new CardFxPricing(merchantAmount, exchanged, markUp, billing,
                rate.midRate(), rate.exchangeRate(), rate.spreadBps(), markUpBps);
    }
}
