package com.revolutclone.card.ledger;

/**
 * The ledger refused a settlement or a release - a 4xx, so a malformed or impossible request.
 *
 * <p>Unlike the hold path, there is no sibling "insufficient funds" case to distinguish: a settlement
 * cannot be declined for lack of money, because the money is already gone as far as the scheme is
 * concerned. So every 4xx here means the same thing - this service asked for something that does not
 * make sense against the ledger's own state (a hold that was already captured, a reservation that
 * never existed, an amount the ledger would not accept) - and all of them are bugs rather than
 * outcomes.
 *
 * <p>Thrown rather than recorded, so the message is retried and then dead-lettered. Silently marking
 * the authorization settled when no posting exists would be far worse than a stuck message: the feed
 * would show a transaction the ledger has never heard of.
 */
public class LedgerRejectedSettlementException extends RuntimeException {

    public LedgerRejectedSettlementException(String message) {
        super(message);
    }
}
