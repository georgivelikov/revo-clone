package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;

import java.util.UUID;

/** Published by {@code payment-service} on {@code payment.event.v1} when the ledger rejects the posting. */
public record PaymentFailedV1(
        UUID paymentId,
        String reason) implements DomainEvent {
}
