package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;

import java.util.UUID;

/**
 * Published by {@code ledger-service} on {@code ledger.entry.v1} when a held reservation is
 * captured, full or partial - {@code amountMinor} is the captured amount, which may be less
 * than the amount originally held.
 */
public record ReservationCapturedV1(
        UUID reservationId,
        UUID accountId,
        long amountMinor,
        String currency) implements DomainEvent {
}
