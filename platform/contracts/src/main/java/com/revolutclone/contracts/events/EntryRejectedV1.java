package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;

import java.util.UUID;

/**
 * Published by {@code ledger-service} on {@code ledger.entry.v1} when a posting is rejected.
 * {@code reason} is one of {@code INSUFFICIENT_FUNDS}, {@code ACCOUNT_FROZEN},
 * {@code ACCOUNT_NOT_FOUND}, {@code CURRENCY_MISMATCH}, {@code RESERVATION_NOT_HELD}.
 *
 * <p>{@code idempotencyKey} is the ledger key the rejected entry would have carried, which is
 * derived from the upstream request ({@code payment:<paymentId>}, {@code fxquote:<quoteId>}) -
 * so it is also how the service that asked for the entry recognises its own rejection coming
 * back on a topic that carries everyone's.
 */
public record EntryRejectedV1(
        String idempotencyKey,
        UUID accountId,
        String reason,
        String referenceId) implements DomainEvent {
}
