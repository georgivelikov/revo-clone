package com.revolutclone.payment.service;

import com.revolutclone.contracts.events.FxExecutionRequestedV1;
import com.revolutclone.contracts.events.PaymentInitiatedV1;
import com.revolutclone.messaging.api.OutboxAppender;
import com.revolutclone.payment.config.PaymentLimitsProperties;
import com.revolutclone.payment.domain.Payment;
import com.revolutclone.payment.domain.PaymentRepository;
import com.revolutclone.payment.fx.FxQuoteHold;
import com.revolutclone.payment.readmodel.AccountView;
import com.revolutclone.payment.readmodel.AccountViewRepository;
import com.revolutclone.payment.web.error.PaymentRejectedException;
import com.revolutclone.payment.web.error.SourceAccountNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step 1 of plans/phase-03-payments-and-ui.md's transfer saga: <em>validate → Payment(PENDING)
 * + outbox in one transaction.</em> A same-currency transfer's outbox row is
 * {@link PaymentInitiatedV1} on {@code payment.event.v1}, exactly as before phase 04.
 *
 * <p><strong>Cross-currency (plans/phase-04-fx-multicurrency.md task 8) reuses fx-service's own
 * posting machinery instead of reinventing it.</strong> {@link #persistCrossCurrency} appends
 * {@link FxExecutionRequestedV1} - the very event fx-service's own {@code QuoteExecutionService}
 * would publish for a self-service exchange - to {@code fx.quote.v1}, keyed by {@code quoteId}.
 * ledger-service's existing consumer for that event does not care which service published it: it
 * captures the reservation named on the event and posts the five-or-six-leg FX entry crediting
 * whatever {@code buyLedgerAccountId} it is given, which by this point is the recipient's, not the
 * payer's. That is "the same saga" the task title promises - one local transaction here, one
 * async consumer elsewhere - built out of the exact pieces task 04.5 already tested, rather than a
 * parallel TRANSFER-shaped multi-currency event ledger-service would need a second posting path
 * for.
 *
 * <p><strong>Validation is split from persistence</strong>, unlike the pre-phase-04 shape of this
 * class: obtaining an fx-service quote is a synchronous HTTP call (plans/phase-04-fx-multicurrency.md's
 * Variant section - "the one sideways HTTP call in the system") that must happen <em>outside</em>
 * any local transaction, for the identical reason {@code QuoteService.quote} itself opens none: a
 * hold cannot be rolled back by a database transaction, so wrapping both would only widen the
 * window in which a local commit fails after fx-service's own funds are already held.
 * {@link #validate} is therefore a plain read (an account lookup is not a write that needs a
 * transaction boundary of its own), and {@link PaymentInitiator} calls the fx-service quote client
 * in between it and whichever {@code persist*} method actually applies.
 */
@Component
public class PaymentTransactionWriter {

    /** Keyed by paymentId, 30-day retention - see plans/phase-00-foundations.md's catalogue. */
    private static final String PAYMENT_TOPIC = "payment.event.v1";

    /** Keyed by quoteId, 30-day retention - the same topic fx-service publishes this event to. */
    private static final String FX_QUOTE_TOPIC = "fx.quote.v1";

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

    /**
     * plans/phase-03-payments-and-ui.md: "source account belongs to the caller, both accounts
     * exist and are ACTIVE, ... amount positive and under a per-transaction cap." Amount
     * positivity is enforced twice over before this point - bean validation on the request DTO and
     * the {@code Payment} constructor's own check - so only the cap is checked here.
     *
     * <p>{@code currency} must still match the <em>source</em> account - a client that thinks it
     * is sending EUR from a GBP account is still a {@code CURRENCY_MISMATCH}, cross-currency or
     * not. A currency mismatch against the <em>target</em> is no longer an error at all: that is
     * what makes a transfer cross-currency rather than rejecting it, per {@link
     * ValidatedTransfer#isCrossCurrency()}.
     *
     * <p>Every fact below comes from the local {@code account_view} read model, never an HTTP call
     * to account-service - "no sideways HTTP" for account data, so a transfer stays acceptable
     * while account-service is down.
     */
    public ValidatedTransfer validate(InitiatePaymentCommand command) {
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
        if (!command.currency().equals(source.getCurrency())) {
            throw new PaymentRejectedException(PaymentRejectionReason.CURRENCY_MISMATCH,
                    "Requested " + command.currency() + " but source account " + source.getAccountId()
                            + " is " + source.getCurrency() + ".");
        }
        if (command.amountMinor() > limits.maxAmountMinor()) {
            throw new PaymentRejectedException(PaymentRejectionReason.AMOUNT_ABOVE_LIMIT,
                    "Amount " + command.amountMinor() + " exceeds the per-transaction cap of "
                            + limits.maxAmountMinor() + " minor units.");
        }
        return new ValidatedTransfer(source, target);
    }

    @Transactional
    public Payment persistSameCurrency(InitiatePaymentCommand command) {
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

        // The ledger key is derived from the payment id, not command.idempotencyKey() - see
        // Payment.ledgerIdempotencyKey()'s own javadoc for why.
        outbox.append(PAYMENT_TOPIC, payment.getId().toString(), new PaymentInitiatedV1(
                payment.getId(),
                payment.getSourceAccountId(),
                payment.getTargetAccountId(),
                payment.getAmountMinor(),
                payment.getCurrency()));

        return payment;
    }

    /**
     * The cross-currency counterpart of {@link #persistSameCurrency}: the payment row records both
     * currencies and the quote behind it, and the outbox event is {@link FxExecutionRequestedV1}
     * on {@code fx.quote.v1} rather than {@link PaymentInitiatedV1} - see this class's own javadoc
     * for why that is the whole saga, reused rather than reinvented.
     *
     * @param quote already obtained by {@link PaymentInitiator}, outside any transaction
     */
    @Transactional
    public Payment persistCrossCurrency(InitiatePaymentCommand command, FxQuoteHold quote) {
        Payment payment = new Payment(
                UUID.randomUUID(),
                command.idempotencyKey(),
                command.initiatorUserId(),
                command.sourceAccountId(),
                command.targetAccountId(),
                command.amountMinor(),
                command.currency(),
                command.reference(),
                quote.buyCurrency(),
                quote.clientBuyMinor(),
                quote.quoteId());

        payments.saveAndFlush(payment);

        outbox.append(FX_QUOTE_TOPIC, quote.quoteId().toString(), new FxExecutionRequestedV1(
                quote.quoteId(),
                command.initiatorUserId(),
                quote.reservationId(),
                quote.buyLedgerAccountId(),
                quote.sellCurrency(),
                quote.sellMinor(),
                quote.buyCurrency(),
                quote.poolMidMinor(),
                quote.clientBuyMinor(),
                quote.feeMinor(),
                quote.pnlMinor()));

        return payment;
    }
}
