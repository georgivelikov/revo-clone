package com.revolutclone.fx.web;

import com.revolutclone.fx.quote.FxQuote;
import com.revolutclone.fx.quote.FxQuoteState;
import java.time.Instant;
import java.util.UUID;

/**
 * What the Exchange screen needs to render a quote and its countdown, and nothing more.
 *
 * <p>{@code feeMinor} is here deliberately, even though it is derivable: the phase requires the spread
 * to be shown explicitly - "You get 85.43 GBP · rate 0.8543 · includes 0.50% fee" - and a UI that had
 * to recompute the fee from two rates would eventually disagree with the ledger about it. It is the
 * same number the {@code FEE_INCOME} leg will carry, taken from the same computation.
 *
 * <p>{@code midRate} is included next to {@code clientRate} so the spread is inspectable rather than
 * merely stated. {@code reservationId} is not: it is the ledger's identifier for a hold the client
 * cannot act on, and the quote id is the only handle execution needs.
 */
public record QuoteResponse(
        UUID quoteId,
        String sellCurrency,
        String buyCurrency,
        long sellMinor,
        long buyMinor,
        String midRate,
        String clientRate,
        int spreadBps,
        long feeMinor,
        FxQuoteState state,
        Instant expiresAt) {

    public static QuoteResponse from(FxQuote quote) {
        return new QuoteResponse(quote.id(), quote.sellCurrency(), quote.buyCurrency(), quote.sellMinor(),
                quote.buyMinor(), quote.midRate(), quote.clientRate(), quote.spreadBps(),
                quote.legs().feeMinor(), quote.state(), quote.expiresAt());
    }
}
