package com.revolutclone.card.ledger;

import java.time.Instant;
import java.util.UUID;

/**
 * A request to hold funds. Field-for-field the ledger's own {@code HoldRequest} (task 02.9), which
 * is deliberate: the adapter serialises this record straight onto the wire, so the two shapes
 * staying identical is visible here rather than hidden in a mapping method.
 *
 * <p>{@code idempotencyKey} is derived from the network's own reference
 * ({@code card-auth:<networkRef>}), never from anything this service made up. The network resends,
 * and a resend must land on the hold the first attempt created rather than on a second hold against
 * the same balance - which is exactly what an idempotency key exists to prevent.
 *
 * <p>{@code expiresAt} is seven days out, per the phase's "ledger: HOLD billing amount, 7-day
 * expiry". The hold's own timeout is owned by the ledger, not by this service: if card-service never
 * comes back, the funds are released regardless.
 */
public record HoldFundsCommand(
        UUID ledgerAccountId,
        long amountMinor,
        String currency,
        UUID referenceId,
        String idempotencyKey,
        Instant expiresAt) {
}
