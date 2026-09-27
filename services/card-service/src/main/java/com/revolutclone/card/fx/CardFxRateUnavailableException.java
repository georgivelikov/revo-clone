package com.revolutclone.card.fx;

/**
 * fx-service could not say what the purchase costs - unreachable, still warming its rate cache from
 * the topic tail, or asked about a pair it has never seen a tick for.
 *
 * <p><strong>A failure, never a decline.</strong> The five decline reasons in
 * plans/phase-05-cards.md's chain are all statements about the card or the holder: not found,
 * frozen, expired, over a limit, out of money. "Our rate feed is behind" is a statement about us,
 * and a decline is final and visible to the holder - the network would treat it as this card's
 * answer for a purchase it should have covered. Thrown instead, so the message is retried and the
 * tap is decided a moment later; the hold is keyed on {@code networkRef}, which is what makes that
 * retry safe.
 *
 * <p>Same reasoning as {@code LedgerUnavailableException} and {@code FundingWalletUnknownException},
 * and it replaces the {@code ForeignCurrencyAuthorizationUnsupportedException} that stood in for
 * this path before the FX path existed.
 */
public class CardFxRateUnavailableException extends RuntimeException {

    public CardFxRateUnavailableException(String message) {
        this(message, null);
    }

    public CardFxRateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
