package com.revolutclone.payment.service;

import java.util.UUID;

/**
 * Everything {@link PaymentInitiator} needs to accept a transfer. {@code initiatorUserId} comes
 * from the authenticated JWT subject and {@code idempotencyKey} from the request header -
 * neither is ever taken from the request body, so a caller cannot initiate on somebody else's
 * behalf or smuggle in a key scoped to another user.
 *
 * <p>Money is a {@code long} of minor units plus an ISO currency code, per CLAUDE.md hard
 * rule 1. It stays that way from the wire to the outbox payload; nothing on this path converts
 * to a decimal type.
 */
public record InitiatePaymentCommand(
        UUID initiatorUserId,
        String idempotencyKey,
        UUID sourceAccountId,
        UUID targetAccountId,
        long amountMinor,
        String currency,
        String reference) {
}
