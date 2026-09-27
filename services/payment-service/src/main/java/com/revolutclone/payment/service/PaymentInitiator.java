package com.revolutclone.payment.service;

import com.revolutclone.payment.domain.Payment;
import com.revolutclone.payment.domain.PaymentRepository;
import com.revolutclone.payment.fx.FxQuoteCommand;
import com.revolutclone.payment.fx.FxQuoteHold;
import com.revolutclone.payment.fx.FxQuoteUnavailableException;
import com.revolutclone.payment.fx.FxQuotes;
import com.revolutclone.payment.web.error.PaymentRejectedException;
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
 * <p><strong>Cross-currency (plans/phase-04-fx-multicurrency.md task 8) inserts one more step
 * between the two reads:</strong> once {@link PaymentTransactionWriter#validate} says the
 * transfer crosses currencies, this class - not the writer - calls fx-service for a quote,
 * because that HTTP call must happen outside any local transaction (see the writer's own
 * javadoc). A quote fx-service will not give is exactly the same shape of rejection as any other
 * {@link PaymentRejectedException}: synchronous, before any row exists.
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
    private final FxQuotes fxQuotes;

    public PaymentInitiator(PaymentRepository payments, PaymentTransactionWriter writer, FxQuotes fxQuotes) {
        this.payments = payments;
        this.writer = writer;
        this.fxQuotes = fxQuotes;
    }

    public Payment initiate(InitiatePaymentCommand command) {
        Optional<Payment> alreadyAccepted = findExisting(command);
        if (alreadyAccepted.isPresent()) {
            return alreadyAccepted.get();
        }
        try {
            ValidatedTransfer validated = writer.validate(command);
            if (!validated.isCrossCurrency()) {
                return writer.persistSameCurrency(command);
            }
            FxQuoteHold quote = obtainQuote(command, validated);
            return writer.persistCrossCurrency(command, quote);
        } catch (DataIntegrityViolationException lostTheRace) {
            return findExisting(command).orElseThrow(() -> lostTheRace);
        }
    }

    private FxQuoteHold obtainQuote(InitiatePaymentCommand command, ValidatedTransfer validated) {
        try {
            return fxQuotes.quote(new FxQuoteCommand(
                    fxQuoteIdempotencyKey(command),
                    command.currency(),
                    validated.target().getCurrency(),
                    command.amountMinor(),
                    validated.target().getUserId()));
        } catch (FxQuoteUnavailableException ex) {
            throw new PaymentRejectedException(PaymentRejectionReason.FX_QUOTE_UNAVAILABLE, ex.getMessage());
        }
    }

    private Optional<Payment> findExisting(InitiatePaymentCommand command) {
        return payments.findByInitiatorUserIdAndIdempotencyKey(
                command.initiatorUserId(), command.idempotencyKey());
    }

    /**
     * {@code fx_quote.idempotency_key} is globally unique, while the client's own header is
     * unique only per initiator - namespacing by {@code initiatorUserId} is what stops two
     * different users retrying with the same header value from colliding there.
     */
    private static String fxQuoteIdempotencyKey(InitiatePaymentCommand command) {
        return "payment:" + command.initiatorUserId() + ":" + command.idempotencyKey();
    }
}
