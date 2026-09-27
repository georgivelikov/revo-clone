package com.revolutclone.fx.ledger;

/**
 * fx-service's view of the ledger's reservation API - a port, so the quoting path depends on "funds
 * can be held" rather than on HTTP, and so a test can substitute a ledger that holds funds without
 * running one.
 *
 * <p>plans/phase-04-fx-multicurrency.md is explicit that quoting must reserve rather than merely
 * lock a rate: "without it, a user can take five quotes against one balance and execute them all.
 * It also gives the compensation path something real to compensate." That makes this the one
 * synchronous hop the quoting path cannot avoid - the caller has to be told *now* whether their
 * funds were actually held, so it cannot go through the outbox.
 *
 * <p>Only {@code hold} lives here for now. Release and capture belong to quote execution and the
 * expiry sweep (tasks 04.5 and 04.6); until then the hold's own 30-second expiry is what unwinds a
 * quote nobody executes, which is the phase's deliberate design: "the reservation expiry means the
 * ledger does not depend on {@code fx-service} ever coming back. Design every hold with a timeout
 * owned by the resource, not by the orchestrator."
 */
public interface LedgerReservations {

    /**
     * Places a hold on the given ledger account.
     *
     * @throws com.revolutclone.fx.quote.error.InsufficientFundsForQuoteException if the account's
     *         available balance will not cover the amount - the case {@code QuoteReservesFundsTest}
     *         drives by taking two quotes against one balance
     * @throws LedgerUnavailableException if the ledger could not be reached or answered with a
     *         server error, so it is unknown whether a hold exists
     * @throws LedgerRejectedHoldException if the ledger refused the request itself (a 4xx other
     *         than insufficient funds) - a bug on this side rather than a user-visible condition
     */
    HeldFunds hold(HoldFundsCommand command);
}
