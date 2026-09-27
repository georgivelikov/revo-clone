package com.revolutclone.fx.web;

import com.revolutclone.fx.quote.FxQuoteProperties;
import com.revolutclone.fx.quote.error.InvalidQuoteRequestException;
import com.revolutclone.fx.quote.error.UnknownCurrencyPairException;
import com.revolutclone.fx.rate.FxRateCache;
import com.revolutclone.money.FxMath;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Step 4 of plans/phase-05-cards.md's authorization flow - "FX if merchant currency != wallet ->
 * fx-service rate, mark up" - seen from this side: a rate, and nothing else.
 *
 * <p><strong>A rate is not a quote, and this is deliberately not one.</strong>
 * {@link InternalFxQuoteController} reserves funds, has a 30-second life and must be executed or
 * expire; a card authorization needs none of that, because the money it moves is already being held
 * by the authorization itself. Handing the card flow a quote would place a second hold on the same
 * wallet for the same purchase. So this is a plain {@code GET}: no idempotency key, no state, no
 * reservation, nothing to compensate.
 *
 * <p><strong>The direction is the one the holder sells in.</strong> A card purchase fixes the
 * <em>buy</em> amount - the merchant asks for exactly $50 - and the wallet supplies whatever that
 * costs, which is why {@code sellCurrency} is the wallet's and {@code buyCurrency} the merchant's.
 * {@code FxMath.applySpread} is always applied in the direction the client sells, so quoting the
 * pair this way round is what puts the spread in the house's favour; the caller inverts the rate to
 * convert the merchant's amount, exactly as {@code QuoteService} does for a client-supplied buy
 * amount. Inverting first and then applying the spread would hand the spread to the holder.
 *
 * <p><strong>Why the spread is applied here and not by the caller.</strong> The exchange spread is
 * fx-service's revenue and fx-service's configuration ({@code revolut.fx.quote.spread-bps}); the
 * card mark-up is card-service's. Task 5 of the phase asks for the two "applied separately", and
 * the cleanest expression of that is that neither service can compute the other's number. This
 * endpoint therefore returns both the mid and the spread-adjusted rate, and card-service adds its
 * mark-up to the converted amount without ever touching the rate.
 *
 * <p>Service-to-service only and not routed by the gateway, same as {@code /internal/fx/quotes} and
 * ledger-service's {@code /internal/ledger/**} - and still an authenticated route rather than one
 * trusting network placement.
 */
@RestController
@RequestMapping("/internal/fx/rates")
public class InternalFxRateController {

    private final FxRateCache rates;
    private final FxQuoteProperties properties;

    public InternalFxRateController(FxRateCache rates, FxQuoteProperties properties) {
        this.rates = rates;
        this.properties = properties;
    }

    @GetMapping("/{sellCurrency}/{buyCurrency}")
    public InternalRateResponse rate(@PathVariable String sellCurrency, @PathVariable String buyCurrency) {
        String sell = sellCurrency.toUpperCase(Locale.ROOT);
        String buy = buyCurrency.toUpperCase(Locale.ROOT);
        if (sell.equals(buy)) {
            throw new InvalidQuoteRequestException("sellCurrency and buyCurrency are both " + sell);
        }

        // Memory, not the fx_rate table - plans/phase-04-fx-multicurrency.md: "Reads for quoting come
        // from memory". An authorization has a two-to-five-second network deadline on it, so this is
        // the one property of this endpoint that is not negotiable.
        String mid = rates.mid(sell, buy).orElseThrow(() -> new UnknownCurrencyPairException(sell, buy));
        int spreadBps = properties.spreadBps();

        return new InternalRateResponse(sell, buy, mid, FxMath.applySpread(mid, spreadBps), spreadBps);
    }
}
