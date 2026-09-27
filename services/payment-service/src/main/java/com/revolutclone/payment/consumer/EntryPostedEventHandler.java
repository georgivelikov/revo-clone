package com.revolutclone.payment.consumer;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.EntryPostedV1;
import com.revolutclone.messaging.api.EventHandler;
import com.revolutclone.payment.domain.Payment;
import com.revolutclone.payment.domain.PaymentRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The success leg of the saga's step 3, for both shapes of payment this service accepts. An
 * {@link EntryPostedV1} means the money has moved, so the payment behind it is COMPLETED. The
 * transition and its outbox event live in {@link PaymentResultApplier}, which is also where the
 * two-events-per-transfer reasoning is written down.
 *
 * <ul>
 *   <li><strong>{@code TRANSFER}</strong> - a same-currency transfer. {@code referenceId} is
 *       {@code journal_entry.reference_id}, which the ledger's own consumer sets to the payment
 *       id directly.</li>
 *   <li><strong>{@code FX}</strong> - a cross-currency transfer (plans/phase-04-fx-multicurrency.md
 *       task 8). {@code referenceId} here is the <em>quote</em> id, not the payment id - the
 *       ledger posts this entry off {@code FxExecutionRequestedV1}, which carries no payment id
 *       at all, because fx-service's own self-service exchange (task 04.5) publishes the same
 *       event with no payment behind it whatsoever. {@link PaymentRepository#findByQuoteId} is
 *       the correlation this service keeps instead.</li>
 * </ul>
 *
 * <p>Every other entry type on this shared topic (funding, fee, card settlement, and an FX entry
 * from a self-service exchange with no matching payment row) is silently ignored - filtering on
 * {@code type} first is stronger than hoping an id never collides.
 */
@Component
public class EntryPostedEventHandler implements EventHandler<EntryPostedV1> {

    private static final Logger log = LoggerFactory.getLogger(EntryPostedEventHandler.class);

    private static final String TRANSFER = "TRANSFER";
    private static final String FX = "FX";

    private final PaymentResultApplier applier;
    private final PaymentRepository payments;

    public EntryPostedEventHandler(PaymentResultApplier applier, PaymentRepository payments) {
        this.applier = applier;
        this.payments = payments;
    }

    @Override
    public void handle(EventEnvelope<EntryPostedV1> envelope) {
        EntryPostedV1 event = envelope.payload();
        if (TRANSFER.equals(event.type())) {
            handleTransfer(event);
        } else if (FX.equals(event.type())) {
            handleFx(event);
        }
    }

    private void handleTransfer(EntryPostedV1 event) {
        UUID paymentId = parseUuid(event, event.referenceId(), "payment");
        if (paymentId != null) {
            applier.complete(paymentId, event.entryId());
        }
    }

    private void handleFx(EntryPostedV1 event) {
        UUID quoteId = parseUuid(event, event.referenceId(), "quote");
        if (quoteId == null) {
            return;
        }
        Optional<Payment> payment = payments.findByQuoteId(quoteId);
        if (payment.isEmpty()) {
            // An FX entry with no matching payment is fx-service's own self-service exchange
            // (task 04.5) - not every FX entry on this topic is a cross-currency transfer, and
            // that is not this service's problem to have an opinion about.
            return;
        }
        applier.complete(payment.get().getId(), event.entryId());
    }

    private static UUID parseUuid(EntryPostedV1 event, String referenceId, String what) {
        if (referenceId == null || referenceId.isBlank()) {
            log.warn("{} entry {} carries no referenceId - cannot tell which {} it settles", event.type(),
                    event.entryId(), what);
            return null;
        }
        try {
            return UUID.fromString(referenceId);
        } catch (IllegalArgumentException notAUuid) {
            log.warn("{} entry {} has referenceId \"{}\", which is not a {} id", event.type(), event.entryId(),
                    referenceId, what);
            return null;
        }
    }
}
