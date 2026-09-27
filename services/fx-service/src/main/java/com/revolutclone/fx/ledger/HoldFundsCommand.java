package com.revolutclone.fx.ledger;

import java.time.Instant;
import java.util.UUID;

/**
 * A request to hold funds. Field-for-field the ledger's own {@code HoldRequest} (task 02.9), which
 * is deliberate: the adapter serialises this record straight onto the wire, so the two shapes
 * staying identical is visible here rather than hidden in a mapping method.
 *
 * <p>{@code idempotencyKey} is derived by fx-service from the quote id, never taken from the
 * client's {@code Idempotency-Key} header - the same rule payment-service follows for its ledger
 * key ({@code payment:<paymentId>}): the ledger's key space is global while a client's key is only
 * unique to that client, so forwarding it would let two callers who picked the same UUID collide on
 * one another's reservation.
 */
public record HoldFundsCommand(
        UUID ledgerAccountId,
        long amountMinor,
        String currency,
        UUID referenceId,
        String idempotencyKey,
        Instant expiresAt) {
}
