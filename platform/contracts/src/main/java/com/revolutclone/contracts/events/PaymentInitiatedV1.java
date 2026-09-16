package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;

import java.util.UUID;

/**
 * Published by {@code payment-service} on {@code payment.event.v1}, keyed by
 * {@code paymentId}; consumed by {@code ledger-service} to post the transfer entry.
 */
public record PaymentInitiatedV1(
        UUID paymentId,
        UUID senderAccountId,
        UUID receiverAccountId,
        long amountMinor,
        String currency) implements DomainEvent {
}
