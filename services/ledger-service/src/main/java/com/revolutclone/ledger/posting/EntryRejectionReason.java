package com.revolutclone.ledger.posting;

/**
 * plans/phase-02-ledger.md's {@code ### Events out} section, {@code EntryRejectedV1}'s reason
 * values. {@link PostingEngine} currently only ever produces {@link #ACCOUNT_NOT_FOUND},
 * {@link #CURRENCY_MISMATCH} and {@link #INSUFFICIENT_FUNDS}: {@link #ACCOUNT_FROZEN} has no
 * backing data yet - {@code ledger_account} carries no frozen/state column in this schema, and
 * a wallet's frozen state (once it exists, in a later phase) will live in whatever service
 * owns that concept - so the value is declared here for forward compatibility with the event
 * contract but is unreachable until that source of truth exists.
 */
public enum EntryRejectionReason {
    INSUFFICIENT_FUNDS,
    ACCOUNT_FROZEN,
    ACCOUNT_NOT_FOUND,
    CURRENCY_MISMATCH
}
