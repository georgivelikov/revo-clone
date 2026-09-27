package com.revolutclone.ledger.posting;

/**
 * plans/phase-02-ledger.md's {@code ### Events out} section, {@code EntryRejectedV1}'s reason
 * values. {@link PostingEngine} currently only ever produces {@link #ACCOUNT_NOT_FOUND},
 * {@link #CURRENCY_MISMATCH} and {@link #INSUFFICIENT_FUNDS}: {@link #ACCOUNT_FROZEN} has no
 * backing data yet - {@code ledger_account} carries no frozen/state column in this schema, and
 * a wallet's frozen state (once it exists, in a later phase) will live in whatever service
 * owns that concept - so the value is declared here for forward compatibility with the event
 * contract but is unreachable until that source of truth exists.
 *
 * <p>{@link #RESERVATION_NOT_HELD} is not produced by {@link PostingEngine} at all: it is the
 * outcome of a <em>capture</em> whose reservation is no longer {@code HELD} by the time the
 * capture transaction reaches it - swept by the expiry job, or already captured or released. It
 * arrived with phase 04's FX execution path, which is the first caller to capture a hold
 * asynchronously and so the first that can lose that race (plans/phase-04-fx-multicurrency.md's
 * "reservation expiry racing execution"). The upstream service needs to tell those apart from a
 * balance problem, since there is nothing left to compensate - the funds are already back.
 */
public enum EntryRejectionReason {
    INSUFFICIENT_FUNDS,
    ACCOUNT_FROZEN,
    ACCOUNT_NOT_FOUND,
    CURRENCY_MISMATCH,
    RESERVATION_NOT_HELD
}
