package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;

import java.util.UUID;

/** Published by {@code identity-service} on {@code identity.user.v1}, keyed by {@code userId}. */
public record UserRegisteredV1(
        UUID userId,
        String email,
        String firstName,
        String lastName,
        String country) implements DomainEvent {
}
