package com.revolutclone.payment.consumer;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.EntryPostedV1;
import com.revolutclone.messaging.api.EventHandler;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The success leg of the saga's step 3: an {@link EntryPostedV1} whose journal entry was a
 * transfer means the money has moved, so the payment behind it is COMPLETED. The transition and
 * its outbox event live in {@link PaymentResultApplier}, which is also where the
 * two-events-per-transfer reasoning is written down.
 *
 * <p>Two filters, both load-bearing, because {@code ledger.entry.v1} is a shared topic:
 * <ul>
 *   <li><strong>{@code type} must be {@code TRANSFER}.</strong> Every funding
 *       ({@code ADJUSTMENT}), fee, FX and card settlement entry lands on this topic too, and none
 *       of them is a payment. Filtering on the journal entry's own type is stronger than hoping
 *       the reference id happens not to collide.</li>
 *   <li><strong>{@code referenceId} must be present and a UUID.</strong> It is
 *       {@code journal_entry.reference_id}, which the ledger's own consumer sets to the payment
 *       id - the only link back. A {@code TRANSFER} without one is a data-shape bug upstream, not
 *       something to guess about.</li>
 * </ul>
 */
@Component
public class EntryPostedEventHandler implements EventHandler<EntryPostedV1> {

    private static final Logger log = LoggerFactory.getLogger(EntryPostedEventHandler.class);

    private static final String TRANSFER = "TRANSFER";

    private final PaymentResultApplier applier;

    public EntryPostedEventHandler(PaymentResultApplier applier) {
        this.applier = applier;
    }

    @Override
    public void handle(EventEnvelope<EntryPostedV1> envelope) {
        EntryPostedV1 event = envelope.payload();
        if (!TRANSFER.equals(event.type())) {
            return;
        }
        if (event.referenceId() == null || event.referenceId().isBlank()) {
            log.warn("TRANSFER entry {} carries no referenceId - cannot tell which payment it settles",
                    event.entryId());
            return;
        }

        UUID paymentId;
        try {
            paymentId = UUID.fromString(event.referenceId());
        } catch (IllegalArgumentException notAUuid) {
            log.warn("TRANSFER entry {} has referenceId \"{}\", which is not a payment id", event.entryId(),
                    event.referenceId());
            return;
        }

        applier.complete(paymentId, event.entryId());
    }
}
