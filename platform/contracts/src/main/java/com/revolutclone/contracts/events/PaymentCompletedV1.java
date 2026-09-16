package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;

import java.util.UUID;

/** Published by {@code payment-service} on {@code payment.event.v1} once the ledger confirms the posting. */
public record PaymentCompletedV1(
        UUID paymentId) implements DomainEvent {
}
