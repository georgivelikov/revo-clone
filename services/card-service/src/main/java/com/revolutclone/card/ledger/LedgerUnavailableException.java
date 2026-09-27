package com.revolutclone.card.ledger;

/**
 * The ledger could not be reached, or answered with a server error, so whether a hold exists is
 * unknown.
 *
 * <p>Deliberately not a decline. The authorization is retried, and because the hold is keyed on
 * {@code card-auth:<networkRef>} a retry that finds the first attempt did place a hold gets that
 * same hold back rather than a second one. Anything stranded is released by the hold's own expiry.
 */
public class LedgerUnavailableException extends RuntimeException {

    public LedgerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
