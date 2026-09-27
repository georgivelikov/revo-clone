package com.revolutclone.card.ledger;

/**
 * The ledger refused the hold request itself - a 4xx that is not insufficient funds, so a malformed
 * or impossible request rather than a holder-visible condition. A bug on this side; it must not be
 * turned into a decline, which would tell the network a good card was refused.
 */
public class LedgerRejectedHoldException extends RuntimeException {

    public LedgerRejectedHoldException(String message) {
        super(message);
    }
}
