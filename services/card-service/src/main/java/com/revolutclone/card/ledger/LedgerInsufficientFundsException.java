package com.revolutclone.card.ledger;

/**
 * The wallet's available balance will not cover the billing amount - the ledger's one documented 409
 * on this endpoint.
 *
 * <p>Not an {@code ApiException}: nothing here is answering an HTTP request. This travels up into
 * the authorization chain, which turns it into {@code DECLINED(INSUFFICIENT_FUNDS)} and a message
 * back to the network. It is the only failure of step 6 that is a decision rather than a fault.
 */
public class LedgerInsufficientFundsException extends RuntimeException {

    public LedgerInsufficientFundsException(String message) {
        super(message);
    }
}
