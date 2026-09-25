package com.revolutclone.payment.domain;

import java.util.UUID;

/**
 * Thrown when something asks a {@link Payment} to make a transition the state machine in
 * {@link PaymentStatus} does not permit - for example a second, contradictory ledger result
 * arriving for a payment that already completed.
 *
 * <p>Extends {@link IllegalStateException} rather than a checked exception on purpose: every
 * caller is either the API (where this is a 409-shaped bug) or a Kafka consumer (where
 * letting it propagate is what puts the record in front of the error handler instead of
 * committing a wrong state). Neither has a sensible {@code catch} that is not itself a bug,
 * so it stays unchecked.
 */
public class IllegalPaymentTransitionException extends IllegalStateException {

    private final UUID paymentId;
    private final PaymentStatus from;
    private final PaymentStatus to;

    public IllegalPaymentTransitionException(UUID paymentId, PaymentStatus from, PaymentStatus to) {
        super("payment " + paymentId + " cannot transition " + from + " -> " + to
                + " (allowed from " + from + ": " + from.allowedTransitions() + ")");
        this.paymentId = paymentId;
        this.from = from;
        this.to = to;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public PaymentStatus getFrom() {
        return from;
    }

    public PaymentStatus getTo() {
        return to;
    }
}
