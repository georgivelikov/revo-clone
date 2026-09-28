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
 * {@link #release} are the settlement consumer's (task 05.6); {@link #expire} is the seven-day
 * sweep's (task 05.7). The hold still carries its own {@code expiresAt} and the ledger still sweeps
 * on it, so the funds come back even if this service never returns - {@link #expire} is what makes
 * that happen at the moment the authorization is concluded rather than whenever the ledger's own
 * sweep next runs, and what lets the row and the hold reach their terminal states together.
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

    /**
     * Expires the hold named by {@code reservationId}: the same return of funds a release performs,
     * asked for because the seven days elapsed rather than because anyone decided anything.
     *
     * <p>Idempotent in the ledger on <em>both</em> terminal states that mean the money is back,
     * {@code EXPIRED} and {@code RELEASED}, which {@link #release} is not. That matters here and not
     * there: the hold's own {@code expires_at} is the same instant this service's sweep works from,
     * so the ledger's scheduled sweep and this call routinely race for the same row, and either
     * order has to end with the authorization concluded.
     *
     * @throws LedgerRejectedSettlementException if the ledger refused the request (a 4xx), which
     *         includes trying to expire a hold that has already been captured - money that has moved
     *         cannot be given back by a sweep
     * @throws LedgerUnavailableException if the ledger could not be reached or answered with a server
     *         error, so it is unknown whether the hold is still standing
     */
    void expire(UUID reservationId);
}
