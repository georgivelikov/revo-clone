package com.revolutclone.payment.consumer;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.EntryRejectedV1;
import com.revolutclone.messaging.api.EventHandler;
import com.revolutclone.payment.domain.Payment;
import com.revolutclone.payment.domain.PaymentRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The failure leg of the saga's step 3, for both shapes of payment this service accepts. The
 * ledger refused to post the entry, so the payment is FAILED with the ledger's own reason
 * carried through verbatim for the UI to show.
 *
 * <ul>
 *   <li><strong>{@code payment:<paymentId>}</strong> - a same-currency transfer. In this shape
 *       <strong>nothing needs undoing</strong>: the ledger posts the whole entry or none of it in
 *       one local transaction, so a rejection has moved no money.</li>
 *   <li><strong>{@code fxquote:<quoteId>}</strong> - a cross-currency transfer
 *       (plans/phase-04-fx-multicurrency.md task 8), the phase's first genuine compensation path.
 *       The quote's reservation <em>was</em> placed - fx-service reserved it when this service
 *       asked for the quote - so something has to give it back. That is fx-service's own job:
 *       its {@code EntryRejectedEventHandler} recognises this exact key too and calls
 *       {@code QuoteExecutionService.compensate}, which cancels the quote and releases the hold.
 *       This handler's only job on that leg is the same as the other one - tell the user, by
 *       failing the payment - which is why it does not also call fx-service itself: the funds
 *       side of compensation is not owned by the service that merely received the notification.
 *   </li>
 * </ul>
 *
 * <p>The event's {@code referenceId} holds the same id as the key and is cross-checked rather
 * than trusted alone, since only the key's format is guaranteed by the ledger's own entry-type
 * validation.
 */
@Component
public class EntryRejectedEventHandler implements EventHandler<EntryRejectedV1> {

    private static final Logger log = LoggerFactory.getLogger(EntryRejectedEventHandler.class);

    private static final String PAYMENT_KEY_PREFIX = "payment:";
    private static final String FX_QUOTE_KEY_PREFIX = "fxquote:";

    private final PaymentResultApplier applier;
    private final PaymentRepository payments;

    public EntryRejectedEventHandler(PaymentResultApplier applier, PaymentRepository payments) {
        this.applier = applier;
        this.payments = payments;
    }

    @Override
    public void handle(EventEnvelope<EntryRejectedV1> envelope) {
        EntryRejectedV1 event = envelope.payload();
        String key = event.idempotencyKey();
        if (key == null) {
            return;
        }
        if (key.startsWith(PAYMENT_KEY_PREFIX)) {
            handleTransferRejection(event, key);
        } else if (key.startsWith(FX_QUOTE_KEY_PREFIX)) {
            handleFxRejection(event, key);
        }
    }

    private void handleTransferRejection(EntryRejectedV1 event, String key) {
        UUID paymentId = parseUuid(key, PAYMENT_KEY_PREFIX);
        if (paymentId == null) {
            return;
        }
        crossCheckReferenceId(event, paymentId);
        applier.fail(paymentId, event.reason());
    }

    private void handleFxRejection(EntryRejectedV1 event, String key) {
        UUID quoteId = parseUuid(key, FX_QUOTE_KEY_PREFIX);
        if (quoteId == null) {
            return;
        }
        Optional<Payment> payment = payments.findByQuoteId(quoteId);
        if (payment.isEmpty()) {
            // fx-service's own self-service exchange (task 04.5) rejections also carry this
            // prefix; not every fxquote: key is a cross-currency transfer.
            return;
        }
        applier.fail(payment.get().getId(), event.reason());
    }

    private static UUID parseUuid(String key, String prefix) {
        try {
            return UUID.fromString(key.substring(prefix.length()));
        } catch (IllegalArgumentException notAUuid) {
            log.warn("ignoring a rejection with key \"{}\" that claims the {} prefix but no id follows it", key,
                    prefix);
            return null;
        }
    }

    private static void crossCheckReferenceId(EntryRejectedV1 event, UUID paymentId) {
        if (event.referenceId() != null && !event.referenceId().equals(paymentId.toString())) {
            log.warn("rejection for payment {} carries a contradictory referenceId \"{}\" - trusting the key",
                    paymentId, event.referenceId());
        }
    }
}
