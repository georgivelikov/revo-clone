package com.revolutclone.card.ledger;

import java.util.UUID;

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
 * <p>{@code hold} is the authorization chain's one synchronous hop; {@link #settle} and
 * {@link #release} are the settlement consumer's (task 05.6). The seven-day expiry sweep is the
 * ledger's own scheduled job and needs nothing here - which is the point of the hold carrying its own
 * {@code expiresAt}: if this service never comes back, the funds are released without it.
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

    /**
     * Settles the hold named by {@code reservationId}: captures what the merchant claimed, releases
     * any difference, and posts any excess. The ledger makes that split against what is actually held,
     * so this call says how much was settled and not how to divide it.
     *
     * <p>Unlike {@link #hold}, no outcome of this is a business decision. The money has already left
     * the holder's card as far as the scheme is concerned, so there is nothing to decline and no
     * insufficient-funds case: an over-settlement is posted regardless, taking the wallet negative if
     * it must, which is why the ledger asks for that permission per entry.
     *
     * @throws LedgerRejectedSettlementException if the ledger refused the request (a 4xx) - a bug on
     *         this side, and never a reason to mark the authorization anything
     * @throws LedgerUnavailableException if the ledger could not be reached or answered with a server
     *         error, so it is unknown whether a posting exists. Safe to retry: both entries are keyed
     *         on {@code networkRef}
     */
    SettledFunds settle(UUID reservationId, SettleFundsCommand command);

    /**
     * Releases the hold named by {@code reservationId} without posting anything - the reversal case,
     * and the one settlement outcome that is the <em>absence</em> of an accounting event.
     *
     * <p>Idempotent in the ledger: releasing an already-released hold is a no-op there, so a resent
     * reversal is safe.
     *
     * @throws LedgerRejectedSettlementException if the ledger refused the request (a 4xx), which
     *         includes trying to release a hold that has already been captured or expired
     * @throws LedgerUnavailableException if the ledger could not be reached or answered with a server
     *         error
     */
    void release(UUID reservationId);
}
