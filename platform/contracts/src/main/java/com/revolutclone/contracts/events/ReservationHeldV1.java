package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;

import java.util.UUID;

/** Published by {@code ledger-service} on {@code ledger.entry.v1} when funds are reserved. */
public record ReservationHeldV1(
        UUID reservationId,
        UUID accountId,
        long amountMinor,
        String currency,
        String referenceId) implements DomainEvent {
}
