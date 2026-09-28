package com.revolutclone.ledger.posting;

/**
 * Whether a particular entry is allowed to push a customer account's {@code available_minor}
 * below zero.
 *
 * <p><strong>This exists because "never negative" is a policy, not a law</strong> - and
 * plans/phase-05-cards.md is explicit about where it must live: "Over-settlement breaking the
 * never-negative rule. The ledger must allow <em>this specific</em> path to overdraw, which means
 * the rule is a policy parameter on the operation, not a hard constraint on the account type. Get
 * this right or you will be tempted to weaken the constraint globally, which undoes Phase 02."
 *
 * <p>So the rule is not relaxed for {@code USER_WALLET}, and no new account type is invented to
 * carry the exception. One {@link PostEntryCommand} at a time says whether it is a debit the wallet
 * may refuse, and the default - the one every existing caller gets, because
 * {@link PostEntryCommand}'s four-argument constructor supplies it - is {@link #FORBID}.
 *
 * <p>The one caller that asks for {@link #ALLOW} today is card settlement's over-settlement leg
 * (task 05.6). A merchant that captures more than it authorized has already taken the money: the
 * choice is not whether the holder pays it but whether the ledger records it, and refusing to
 * record it would mean the bank's books disagree with the scheme's. See
 * {@code CardSettlementService}, which is also where the overdraw is flagged rather than swallowed.
 */
public enum OverdraftPolicy {

    /**
     * A non-capturing {@code DEBIT} against a {@code USER_WALLET} or {@code VAULT} is rejected with
     * {@code INSUFFICIENT_FUNDS} when {@code available_minor} will not cover it. Every ordinary
     * money movement - transfers, FX, fees - uses this.
     */
    FORBID,

    /**
     * The same debit is posted regardless, taking {@code available_minor} negative if it must.
     * Nothing else about the entry changes: it still has to balance per currency, the deferred
     * {@code posting_balanced} trigger still re-checks it at commit, and the zero-sum invariant is
     * untouched - a negative balance is a fact about one account, not a hole in the books.
     */
    ALLOW
}
