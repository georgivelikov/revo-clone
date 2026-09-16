package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;

import java.util.UUID;

/** Published by {@code ledger-service} on {@code ledger.entry.v1} when a hold is released. */
public record ReservationReleasedV1(
        UUID reservationId,
        UUID accountId,
        String reason) implements DomainEvent {
}
