package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;

import java.util.UUID;

/** Published by {@code account-service} on {@code account.account.v1}, keyed by {@code accountId}. */
public record AccountOpenedV1(
        UUID accountId,
        UUID userId,
        String currency,
        String type) implements DomainEvent {
}
