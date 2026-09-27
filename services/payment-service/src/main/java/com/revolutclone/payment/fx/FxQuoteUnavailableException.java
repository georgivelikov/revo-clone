package com.revolutclone.payment.fx;

/**
 * fx-service refused the quote, or could not be reached at all - the circuit breaker's own
 * fallback throws this too. Either way the caller's only correct response is to reject the
 * transfer before any {@code payment} row is written: plans/phase-04-fx-multicurrency.md's Variant
 * section is explicit that the user must see a firm rate before confirming, so a quote that cannot
 * be obtained means there is no transfer to accept yet, not a transfer to accept and hope about.
 */
public class FxQuoteUnavailableException extends RuntimeException {

    public FxQuoteUnavailableException(String message) {
        super(message);
    }

    public FxQuoteUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
