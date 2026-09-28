package com.revolutclone.card.ledger;

/**
 * A request to settle an authorization's hold. Field-for-field the ledger's own
 * {@code CardSettlementRequest} (task 05.6), the same way {@link HoldFundsCommand} mirrors its
 * {@code HoldRequest} - the adapter serialises this record straight onto the wire, so the two shapes
 * staying identical is visible here rather than hidden in a mapping method.
 *
 * <p><strong>Both amounts are in the wallet's currency, not the merchant's.</strong> The merchant's
 * figure is what the network sent and what the holder's feed shows; what the ledger moves is the
 * billing amount, derived on this side from the authorization's own recorded amounts so the
 * conversion the holder was shown at the till is the one that settles.
 *
 * @param settledMinor what the merchant captured, converted to the wallet's currency. May be under
 *                     the hold (a partial) or over it (a tip) - the ledger splits it against what is
 *                     actually held, because only the ledger knows that
 * @param feeMinor     the slice of {@code settledMinor} that is the card mark-up, bound for
 *                     {@code system:fee-income} rather than {@code system:card-settlement}. Zero for
 *                     a domestic purchase, which has no mark-up at all
 * @param networkRef   the network's own reference, from which the ledger derives the entry's
 *                     {@code cardauth:} idempotency key. The network resends settlements too, and a
 *                     resend must land on the entry the first attempt posted
 */
public record SettleFundsCommand(long settledMinor, long feeMinor, String networkRef) {
}
