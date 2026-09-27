package com.revolutclone.fx.web;

import com.revolutclone.fx.quote.FxLegs;
import com.revolutclone.fx.quote.FxQuote;
import com.revolutclone.fx.quote.FxQuoteState;
import java.time.Instant;
import java.util.UUID;

/**
 * The internal counterpart of {@link QuoteResponse}: everything a trusted service caller needs to
 * build {@code FxExecutionRequestedV1} itself, none of which the public response exposes - see its
 * own javadoc for why {@code reservationId} is deliberately withheld there. This is the shape
 * payment-service's cross-currency transfer (plans/phase-04-fx-multicurrency.md task 8) consumes,
 * with {@code buyLedgerAccountId} already resolved against the transfer's recipient rather than
 * the caller.
 */
public record InternalQuoteResponse(
        UUID quoteId,
        UUID reservationId,
        UUID buyLedgerAccountId,
        String sellCurrency,
        long sellMinor,
        String buyCurrency,
        long poolMidMinor,
        long clientBuyMinor,
        long feeMinor,
        long pnlMinor,
        FxQuoteState state,
        Instant expiresAt) {

    public static InternalQuoteResponse of(FxQuote quote, UUID buyLedgerAccountId) {
        FxLegs legs = quote.legs();
        return new InternalQuoteResponse(quote.id(), quote.reservationId(), buyLedgerAccountId,
                quote.sellCurrency(), quote.sellMinor(), quote.buyCurrency(), legs.poolMidMinor(),
                legs.clientBuyMinor(), legs.feeMinor(), legs.pnlMinor(), quote.state(), quote.expiresAt());
    }
}
