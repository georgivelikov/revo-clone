package com.revolutclone.payment.service;

import com.revolutclone.payment.domain.Payment;
import com.revolutclone.payment.domain.PaymentRepository;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Layer 2 of plans/phase-03-payments-and-ui.md's three-layer idempotency - "the layer that
 * actually guarantees correctness; Redis is an optimisation and may be flushed at any time".
 * The guarantee is the database's, not this class's: {@code unique (initiator_user_id,
 * idempotency_key)} is what makes a duplicate impossible. This class only decides what a
 * caller who lost that race is told.
 *
 * <p>Two reads around one write, and both are load-bearing:
 * <ul>
 *   <li>The read <em>before</em> the insert handles the ordinary duplicate - a retried request,
 *       a double-clicked button whose first response was slow - without doing any work or
 *       burning a sequence value. It is a fast path, not the correctness mechanism.</li>
 *   <li>The read <em>after</em> catching {@link DataIntegrityViolationException} handles the
 *       genuine race, where two concurrent requests both passed the first read and the database
 *       picked a winner. It must run in a new transaction, which it does: the insert's
 *       transaction is already rolled back by the time this catch block runs, because it lives
 *       on the far side of {@link PaymentTransactionWriter}'s proxy.</li>
 * </ul>
 *
 * <p>Both paths return the winning payment and the caller returns it as a 202 - a duplicate is
 * not an error, it is the same answer again. If the re-read finds nothing, the violation was
 * some <em>other</em> constraint and the original exception is rethrown rather than being
 * swallowed into a misleading "payment not found".
 *
 * <p>Deliberately no body comparison here: replaying the old response for the same key with a
 * <em>different</em> body is a 422 at the gateway (task 03.5), where the request-body hash
 * lives. Duplicating that check here would need this service to keep its own hash of a body it
 * has already parsed away.
 */
@Service
public class PaymentInitiator {

    private final PaymentRepository payments;
    private final PaymentTransactionWriter writer;

    public PaymentInitiator(PaymentRepository payments, PaymentTransactionWriter writer) {
        this.payments = payments;
        this.writer = writer;
    }

    public Payment initiate(InitiatePaymentCommand command) {
        Optional<Payment> alreadyAccepted = findExisting(command);
        if (alreadyAccepted.isPresent()) {
            return alreadyAccepted.get();
        }
        try {
            return writer.insertPendingWithOutbox(command);
        } catch (DataIntegrityViolationException lostTheRace) {
            return findExisting(command).orElseThrow(() -> lostTheRace);
        }
    }

    private Optional<Payment> findExisting(InitiatePaymentCommand command) {
        return payments.findByInitiatorUserIdAndIdempotencyKey(
                command.initiatorUserId(), command.idempotencyKey());
    }
}
