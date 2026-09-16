package com.revolutclone.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * The one shape every event on every topic carries. {@code type} is the payload's simple
 * class name (e.g. {@code "PaymentInitiatedV1"}) so a consumer can dispatch before
 * deserializing the payload itself.
 */
public record EventEnvelope<T extends DomainEvent>(
        UUID eventId,
        Instant occurredAt,
        String correlationId,
        String type,
        int version,
        T payload) {
}
