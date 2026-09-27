package com.revolutclone.fx.ledger;

import java.util.UUID;

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
 * <p>There is deliberately no {@code capture} here. Executing a quote does not call the ledger at
 * all: it emits {@code FxExecutionRequestedV1} through the outbox and the ledger captures the hold
 * itself, because that write must be atomic with marking the quote {@code CONSUMED} (CLAUDE.md
 * rule 6). {@code release} is a synchronous call for the opposite reason - it is compensation, run
 * after the fact from a Kafka consumer or the expiry sweep, where there is no local transaction for
 * it to be atomic with and the outcome matters immediately.
 *
 * <p>Neither path is load-bearing for correctness: if this service never comes back, the hold's own
 * 30-second expiry releases it anyway, which is the phase's deliberate design - "the reservation
 * expiry means the ledger does not depend on {@code fx-service} ever coming back. Design every hold
 * with a timeout owned by the resource, not by the orchestrator."
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

    /**
     * Releases a hold this service placed, returning the funds to {@code available}. Used by the
     * compensation path when the ledger has rejected an execution the quote was already
     * {@code CONSUMED} for.
     *
     * <p>Best effort by contract: a reservation that has already expired, been released or been
     * captured is not an error here, because every one of those outcomes means the funds are no
     * longer held and there is nothing left to compensate. Only "the ledger could not be reached"
     * is worth a caller's attention, and even that resolves itself when the hold expires.
     *
     * @throws LedgerUnavailableException if the ledger could not be reached or answered with a
     *         server error, so it is unknown whether the hold is still standing
     */
    void release(UUID reservationId);
}
