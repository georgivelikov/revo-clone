package com.revolutclone.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The test plans/phase-03-payments-and-ui.md's ## Tests table names, asserting
 * "COMPLETED -&gt; FAILED throws". That single case is the headline, but the interesting
 * property is the general one it stands for: the three terminal states are genuinely
 * terminal, so this covers the whole transition matrix rather than only the named pair.
 *
 * <p>Pure unit test - no Spring context, no database. The state machine is domain logic and
 * must hold before anything is persisted; the schema's own agreement with the entity is
 * proven separately by {@code PaymentSchemaMigrationTest}.
 */
class PaymentStateMachineTest {

    private static Payment pendingPayment() {
        return new Payment(UUID.randomUUID(), "idem-" + UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1_000L, "EUR", "rent");
    }

    @Test
    void aNewPaymentStartsPending() {
        assertThat(pendingPayment().getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void completedToFailedThrows() {
        Payment payment = pendingPayment();
        payment.markCompleted(UUID.randomUUID());

        assertThatThrownBy(() -> payment.markFailed("INSUFFICIENT_FUNDS"))
                .isInstanceOf(IllegalPaymentTransitionException.class)
                .hasMessageContaining("COMPLETED -> FAILED");
    }

    @Test
    void anIllegalTransitionLeavesTheStateAndItsFieldsUntouched() {
        Payment payment = pendingPayment();
        UUID entryId = UUID.randomUUID();
        payment.markCompleted(entryId);

        assertThatThrownBy(() -> payment.markFailed("INSUFFICIENT_FUNDS"))
                .isInstanceOf(IllegalPaymentTransitionException.class);

        // The throw has to happen before any mutation, or a rejected transition still leaves
        // a half-changed payment behind - worse than the silent update the spec forbids.
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getFailureReason()).isNull();
        assertThat(payment.getLedgerEntryId()).isEqualTo(entryId);
    }

    @Nested
    class FromPending {

        @Test
        void toCompletedRecordsTheLedgerEntry() {
            Payment payment = pendingPayment();
            UUID entryId = UUID.randomUUID();

            payment.markCompleted(entryId);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
            assertThat(payment.getLedgerEntryId()).isEqualTo(entryId);
            assertThat(payment.getFailureReason()).isNull();
        }

        @Test
        void toFailedRecordsTheReason() {
            Payment payment = pendingPayment();

            payment.markFailed("INSUFFICIENT_FUNDS");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(payment.getLedgerEntryId()).isNull();
        }

        @Test
        void toCancelled() {
            Payment payment = pendingPayment();

            payment.markCancelled();

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        }

    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void nothingEverTransitionsBackToPending(PaymentStatus from) {
        // Including PENDING itself: a self-transition is not in the spec's allowed list.
        // Redelivery is deduplicated by processed_event one layer up, not by making the state
        // machine tolerant. There is deliberately no mark-pending method to call.
        assertThat(from.canTransitionTo(PaymentStatus.PENDING)).isFalse();
    }

    /** Every move out of a terminal state, including the named COMPLETED -&gt; FAILED. */
    static Stream<org.junit.jupiter.params.provider.Arguments> illegalTerminalTransitions() {
        return Stream.of(PaymentStatus.COMPLETED, PaymentStatus.FAILED, PaymentStatus.CANCELLED)
                .flatMap(from -> Stream.of(PaymentStatus.COMPLETED, PaymentStatus.FAILED,
                                PaymentStatus.CANCELLED)
                        .map(to -> org.junit.jupiter.params.provider.Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1} is rejected")
    @MethodSource("illegalTerminalTransitions")
    void everyTransitionOutOfATerminalStateIsRejected(PaymentStatus from, PaymentStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();

        Payment payment = pendingPayment();
        moveTo(payment, from);

        assertThatThrownBy(() -> moveTo(payment, to))
                .isInstanceOf(IllegalPaymentTransitionException.class);
        assertThat(payment.getStatus()).isEqualTo(from);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class,
            names = {"COMPLETED", "FAILED", "CANCELLED"})
    void terminalStatesReportThemselvesTerminal(PaymentStatus status) {
        assertThat(status.isTerminal()).isTrue();
        assertThat(status.allowedTransitions()).isEmpty();
    }

    @Test
    void pendingIsTheOnlyNonTerminalState() {
        assertThat(PaymentStatus.PENDING.isTerminal()).isFalse();
        assertThat(PaymentStatus.PENDING.allowedTransitions())
                .containsExactlyInAnyOrder(PaymentStatus.COMPLETED, PaymentStatus.FAILED,
                        PaymentStatus.CANCELLED);
    }

    @Test
    void theExceptionNamesThePaymentAndBothStates() {
        Payment payment = pendingPayment();
        payment.markCancelled();

        assertThatThrownBy(() -> payment.markCompleted(UUID.randomUUID()))
                .isInstanceOf(IllegalPaymentTransitionException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                        .type(IllegalPaymentTransitionException.class))
                .satisfies(ex -> {
                    assertThat(ex.getPaymentId()).isEqualTo(payment.getId());
                    assertThat(ex.getFrom()).isEqualTo(PaymentStatus.CANCELLED);
                    assertThat(ex.getTo()).isEqualTo(PaymentStatus.COMPLETED);
                });
    }

    @Test
    void aNonPositiveAmountIsRejectedAtConstruction() {
        // Mirrors the `check (amount_minor > 0)` in V3__payment.sql, so a bad amount fails in
        // the domain rather than as an opaque constraint violation at flush time.
        assertThatThrownBy(() -> new Payment(UUID.randomUUID(), "k", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 0L, "EUR", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theLedgerKeyIsDerivedFromThePaymentNotTheClientHeader() {
        // The spec's one-line rule: journal_entry.idempotency_key is globally unique, so two
        // users choosing the same client key must not collide there.
        Payment payment = pendingPayment();

        assertThat(payment.ledgerIdempotencyKey()).isEqualTo("payment:" + payment.getId());
        assertThat(payment.ledgerIdempotencyKey()).doesNotContain(payment.getIdempotencyKey());
    }

    /** {@code PENDING} is absent on purpose: no method moves a payment back to it. */
    private static void moveTo(Payment payment, PaymentStatus target) {
        switch (target) {
            case COMPLETED -> payment.markCompleted(UUID.randomUUID());
            case FAILED -> payment.markFailed("INSUFFICIENT_FUNDS");
            case CANCELLED -> payment.markCancelled();
            case PENDING -> throw new IllegalArgumentException("no transition targets PENDING");
        }
    }
}
