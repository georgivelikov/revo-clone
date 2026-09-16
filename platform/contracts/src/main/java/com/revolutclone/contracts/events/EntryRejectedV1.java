package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;

import java.util.UUID;

/**
 * Published by {@code ledger-service} on {@code ledger.entry.v1} when a posting is rejected.
 * {@code reason} is one of {@code INSUFFICIENT_FUNDS}, {@code ACCOUNT_FROZEN},
 * {@code ACCOUNT_NOT_FOUND}, {@code CURRENCY_MISMATCH}.
 */
public record EntryRejectedV1(
        String idempotencyKey,
        UUID accountId,
        String reason,
        String referenceId) implements DomainEvent {
}
