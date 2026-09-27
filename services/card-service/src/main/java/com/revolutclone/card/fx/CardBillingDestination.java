package com.revolutclone.card.fx;

/**
 * Where each part of what the holder is charged lands in the ledger when the authorization settles.
 *
 * <p>Deliberately card-service's own enum rather than the ledger's account types: the ledger's are
 * the ledger's, and CLAUDE.md rule 7 means this service cannot reach into them. Same precedent as
 * fx-service's own {@code FxLegs.Role}.
 *
 * <p>These two exhaust an authorization's billing amount, which is the point of naming them at all.
 * plans/phase-05-cards.md's settlement posting is "DEBIT user wallet / CREDIT
 * system:card-settlement" for a domestic purchase; a foreign one splits the credit side, because the
 * mark-up is revenue and the rest is the merchant's money passing through. Task 05.6 posts it. This
 * task computes it and records it, so that a settlement days later has nothing left to decide - see
 * {@link CardFxPricer} on holding and settling at the auth rate.
 */
public enum CardBillingDestination {

    /**
     * The converted purchase: what the merchant is owed, expressed in the wallet's currency.
     * {@code CREDIT system:card-settlement}.
     */
    CARD_SETTLEMENT,

    /** The card mark-up, as revenue. {@code CREDIT system:fee-income}. */
    FEE_INCOME
}
