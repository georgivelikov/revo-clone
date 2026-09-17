package com.revolutclone.ledger.domain;

/**
 * plans/phase-02-ledger.md's ### Accounting sign convention, verbatim:
 *
 * <p><b>A user wallet is a liability of the bank to the customer. A CREDIT increases the
 * customer's money; a DEBIT decreases it.</b>
 *
 * <p>So a transfer from Alice to Bob is {@code DEBIT alice_wallet} / {@code CREDIT
 * bob_wallet}. Getting this backwards is the single most common bug in ledger
 * implementations, and it is invisible until you look at a statement.
 *
 * <p>{@code amount_minor} is always positive; direction is carried by this enum, never by a
 * signed amount. Allowing signed amounts and a direction column would give two ways to
 * express the same thing, and they would eventually disagree.
 */
public enum PostingDirection {
    DEBIT,
    CREDIT
}
