package com.revolutclone.analytics.payment;

import java.util.UUID;

/**
 * Everything {@link PaymentInitiationClient} needs to initiate a transfer on a user's behalf -
 * analytics-service's own copy of the shape payment-service's internal endpoint accepts, not a
 * shared type between services (CLAUDE.md rule 7: every service owns its own database, and by the
 * same logic its own contract types for a call it makes).
 *
 * <p>Money is a {@code long} of minor units plus an ISO currency code, per CLAUDE.md hard rule 1.
 *
 * <p>Built generally, not vault-specific: {@link VaultTransferService} is the first caller (task
 * 06.6), and task 06.7's round-up rule is the second, from a Kafka-consumer context with no user
 * in the picture at all.
 */
public record InitiatePaymentCommand(
        UUID userId,
        String idempotencyKey,
        UUID sourceAccountId,
        UUID targetAccountId,
        long amountMinor,
        String currency,
        String reference) {
}
