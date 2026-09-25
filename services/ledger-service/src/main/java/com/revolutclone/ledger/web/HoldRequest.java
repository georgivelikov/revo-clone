package com.revolutclone.ledger.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code referenceId} is {@code @NotNull}, not optional: {@code reservation.reference_id} is
 * {@code not null} in the schema (unlike {@code journal_entry.reference_id}, which is nullable)
 * - found live when the DB rejected a hold request that omitted it.
 */
public record HoldRequest(
        @NotNull UUID ledgerAccountId,
        @Positive long amountMinor,
        @NotBlank String currency,
        @NotNull UUID referenceId,
        @NotBlank String idempotencyKey,
        @NotNull Instant expiresAt) {
}
