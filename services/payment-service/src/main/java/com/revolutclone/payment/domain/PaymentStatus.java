package com.revolutclone.payment.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The payment state machine, from plans/phase-03-payments-and-ui.md's
 * {@code ### payment-service} section, reproduced exactly:
 *
 * <pre>
 * PENDING -&gt; COMPLETED | FAILED | CANCELLED
 * COMPLETED, FAILED, CANCELLED are terminal
 * </pre>
 *
 * <p>Declared here as data rather than scattered across {@code if} statements in the saga's
 * consumers, so that there is exactly one place to read to know what a payment may do next.
 * The phase spec is explicit that an illegal transition <em>throws</em> rather than silently
 * updating: a late or duplicated ledger result arriving for a payment that already settled is
 * a correctness problem worth surfacing, and a silent no-op would hide it behind a payment
 * whose status simply looks wrong later.
 *
 * <p>Note that a self-transition is illegal too - {@code PENDING -> PENDING} and
 * {@code COMPLETED -> COMPLETED} both throw. Redelivery is not handled by making transitions
 * tolerant; it is handled one layer up by {@code processed_event} (the consumer idempotency
 * layer from phase 00), which is the mechanism that actually guarantees a redelivered event
 * does no work at all.
 */
public enum PaymentStatus {

    /** Accepted by the API, ledger result not yet known. The only non-terminal state. */
    PENDING,

    /** The ledger posted the journal entry. Terminal. */
    COMPLETED,

    /** The ledger rejected the entry, or validation failed after acceptance. Terminal. */
    FAILED,

    /** Withdrawn before settling. Terminal. */
    CANCELLED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS = Map.of(
            PENDING, EnumSet.of(COMPLETED, FAILED, CANCELLED),
            COMPLETED, EnumSet.noneOf(PaymentStatus.class),
            FAILED, EnumSet.noneOf(PaymentStatus.class),
            CANCELLED, EnumSet.noneOf(PaymentStatus.class));

    /** True when no transition out of this state is legal. */
    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }

    /** True when {@code target} is a legal next state from this one. */
    public boolean canTransitionTo(PaymentStatus target) {
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    /** The legal next states from this one; empty for a terminal state. */
    public Set<PaymentStatus> allowedTransitions() {
        return ALLOWED_TRANSITIONS.get(this);
    }
}
