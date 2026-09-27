package com.revolutclone.card.ledger;

/**
 * card-service's view of the ledger's reservation API - a port, so the authorization chain depends
 * on "funds can be held" rather than on HTTP, and so a test can substitute a ledger that really
 * holds funds without running one.
 *
 * <p>Step 6 of plans/phase-05-cards.md's flow is the one synchronous hop the decision cannot avoid:
 * the network is waiting for a yes or no and the answer depends on the wallet's available balance,
 * which this service does not hold and must not. It therefore cannot go through the outbox - there
 * is no later to defer to.
 *
 * <p>Deliberately only {@code hold}. Capture and release belong to the settlement consumer (task
 * 05.6) and the expiry sweep (task 05.7); adding them before there is a caller would be inventing a
 * contract for work that has not been designed yet.
 */
public interface LedgerReservations {

    /**
     * Places a hold on the card's funding wallet for the billing amount.
     *
     * @throws LedgerInsufficientFundsException if the wallet's available balance will not cover the
     *         amount - the one outcome of this call that is a business decline
     *         ({@code DECLINED(INSUFFICIENT_FUNDS)}) rather than a failure
     * @throws LedgerRejectedHoldException if the ledger refused the request itself (a 4xx other than
     *         insufficient funds) - a bug on this side rather than a holder-visible condition
     * @throws LedgerUnavailableException if the ledger could not be reached or answered with a server
     *         error, so it is unknown whether a hold exists
     */
    HeldFunds hold(HoldFundsCommand command);
}
