package com.revolutclone.payment.service;

import com.revolutclone.contracts.events.PaymentInitiatedV1;
import com.revolutclone.messaging.api.OutboxAppender;
import com.revolutclone.payment.config.PaymentLimitsProperties;
import com.revolutclone.payment.domain.Payment;
import com.revolutclone.payment.domain.PaymentRepository;
import com.revolutclone.payment.readmodel.AccountView;
import com.revolutclone.payment.readmodel.AccountViewRepository;
import com.revolutclone.payment.web.error.PaymentRejectedException;
import com.revolutclone.payment.web.error.SourceAccountNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step 1 of plans/phase-03-payments-and-ui.md's transfer saga: <em>validate → Payment(PENDING)
 * + outbox PaymentInitiatedV1, in one transaction.</em> The single {@code @Transactional}
 * boundary is the entire point of this class - the payment row and the event that tells the
 * ledger to move the money either both commit or neither does. Publishing straight to the
 * broker here instead of going through the outbox would reintroduce exactly the dual-write this
 * architecture exists to avoid (CLAUDE.md hard rule 6).
 *
 * <p>Split out from {@link PaymentInitiator} rather than being a {@code @Transactional} method
 * on it, for the same reason {@code ledger-service} splits {@code PostingTransactionExecutor}
 * out of {@code PostingEngine}: the caller has to catch the unique-constraint violation this
 * insert can throw, and a transaction that has caught a constraint violation is already marked
 * rollback-only, so the "read back the winner" query has to run in a different transaction on
 * the far side of this proxy boundary. Self-invocation would not even apply the annotation.
 *
 * <p><strong>Validation is inside the transaction, before the insert.</strong> A rejected
 * request must leave nothing behind - no payment row, no outbox row - because an outbox row is
 * an instruction to move money that nothing downstream will second-guess.
 */
@Component
public class PaymentTransactionWriter {

    /** Keyed by paymentId, 30-day retention - see plans/phase-00-foundations.md's catalogue. */
    private static final String TOPIC = "payment.event.v1";

    private final PaymentRepository payments;
    private final AccountViewRepository accountViews;
    private final OutboxAppender outbox;
    private final PaymentLimitsProperties limits;

    public PaymentTransactionWriter(PaymentRepository payments, AccountViewRepository accountViews,
            OutboxAppender outbox, PaymentLimitsProperties limits) {
        this.payments = payments;
        this.accountViews = accountViews;
        this.outbox = outbox;
        this.limits = limits;
    }

    @Transactional
    public Payment insertPendingWithOutbox(InitiatePaymentCommand command) {
        validate(command);

        Payment payment = new Payment(
                UUID.randomUUID(),
                command.idempotencyKey(),
                command.initiatorUserId(),
                command.sourceAccountId(),
                command.targetAccountId(),
                command.amountMinor(),
                command.currency(),
                command.reference());

        // Flush rather than defer to commit, so a duplicate (initiator_user_id,
        // idempotency_key) surfaces as DataIntegrityViolationException here - where
        // PaymentInitiator can turn it back into the winning payment - instead of at commit
        // time, after the outbox row has already been added to the persistence context.
        payments.saveAndFlush(payment);

        // The ledger key is NOT command.idempotencyKey(). That key is unique only per
        // initiator, while journal_entry.idempotency_key is globally unique, so two users
        // choosing the same UUID would collide in the ledger and one would silently receive
        // the other's journal entry. The ledger key is derived from the payment id instead -
        // Payment.ledgerIdempotencyKey(), "payment:<paymentId>", which ledger-service's
        // PostingTransactionExecutor also requires as the prefix for a TRANSFER entry. It is
        // not carried on the event at all: paymentId is on the event, and the ledger derives
        // the same string from it, so the client's header never leaves this service.
        outbox.append(TOPIC, payment.getId().toString(), new PaymentInitiatedV1(
                payment.getId(),
                payment.getSourceAccountId(),
                payment.getTargetAccountId(),
                payment.getAmountMinor(),
                payment.getCurrency()));

        return payment;
    }

    /**
     * plans/phase-03-payments-and-ui.md: "source account belongs to the caller, both accounts
     * exist and are ACTIVE, currencies match, amount positive and under a per-transaction cap."
     * Amount positivity is enforced twice over before this point - bean validation on the
     * request DTO and the {@code Payment} constructor's own check - so only the cap is left
     * here.
     *
     * <p>Every fact below comes from the local {@code account_view} read model, never an HTTP
     * call to account-service. That is the "no sideways HTTP" rule doing real work: a transfer
     * stays acceptable while account-service is down, and the read model rebuilds itself from
     * the compacted {@code account.account.v1} topic on startup.
     */
    private void validate(InitiatePaymentCommand command) {
        AccountView source = accountViews.findById(command.sourceAccountId())
                .filter(view -> view.isOwnedBy(command.initiatorUserId()))
                .orElseThrow(() -> new SourceAccountNotFoundException(command.sourceAccountId()));

        if (!source.isActive()) {
            throw new PaymentRejectedException(PaymentRejectionReason.SOURCE_ACCOUNT_NOT_ACTIVE,
                    "Source account " + source.getAccountId() + " is " + source.getState() + ".");
        }
        if (command.sourceAccountId().equals(command.targetAccountId())) {
            throw new PaymentRejectedException(PaymentRejectionReason.SAME_ACCOUNT,
                    "Source and target account are the same account.");
        }

        AccountView target = accountViews.findById(command.targetAccountId())
                .orElseThrow(() -> new PaymentRejectedException(
                        PaymentRejectionReason.TARGET_ACCOUNT_NOT_FOUND,
                        "No account " + command.targetAccountId() + " is known to this service."));

        if (!target.isActive()) {
            throw new PaymentRejectedException(PaymentRejectionReason.TARGET_ACCOUNT_NOT_ACTIVE,
                    "Target account " + target.getAccountId() + " is " + target.getState() + ".");
        }
        if (!command.currency().equals(source.getCurrency()) || !command.currency().equals(target.getCurrency())) {
            throw new PaymentRejectedException(PaymentRejectionReason.CURRENCY_MISMATCH,
                    "Requested " + command.currency() + " but source is " + source.getCurrency()
                            + " and target is " + target.getCurrency()
                            + "; cross-currency transfers arrive in phase 04.");
        }
        if (command.amountMinor() > limits.maxAmountMinor()) {
            throw new PaymentRejectedException(PaymentRejectionReason.AMOUNT_ABOVE_LIMIT,
                    "Amount " + command.amountMinor() + " exceeds the per-transaction cap of "
                            + limits.maxAmountMinor() + " minor units.");
        }
    }
}
