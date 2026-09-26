package com.revolutclone.payment.consumer;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.EntryRejectedV1;
import com.revolutclone.messaging.api.EventHandler;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The failure leg of the saga's step 3: the ledger refused to post the entry, so the payment is
 * FAILED with the ledger's own reason ({@code INSUFFICIENT_FUNDS}, {@code ACCOUNT_NOT_FOUND},
 * {@code CURRENCY_MISMATCH}, {@code ACCOUNT_FROZEN}) carried through verbatim for the UI to show.
 * <strong>Nothing needs undoing:</strong> in the same-currency case the ledger posts the whole
 * entry or none of it in one local transaction, so a rejection has moved no money and there is no
 * compensation step - that arrives in phase 04, when FX adds a second resource.
 *
 * <p>{@link EntryRejectedV1} carries no entry id and no {@code type} (there is no entry - nothing
 * was written), so the link back to the payment is the <strong>idempotency key</strong>:
 * {@code payment:<paymentId>}, derived by both services from the payment id alone. That prefix is
 * also the filter that keeps this handler out of rejections belonging to FX quotes
 * ({@code fxquote:}), card authorizations ({@code cardauth:}) and admin adjustments, which share
 * this topic. The event's {@code referenceId} holds the same payment id and is cross-checked
 * rather than trusted alone, since only the key's format is guaranteed by the ledger's own
 * {@code TRANSFER}-entry validation.
 */
@Component
public class EntryRejectedEventHandler implements EventHandler<EntryRejectedV1> {

    private static final Logger log = LoggerFactory.getLogger(EntryRejectedEventHandler.class);

    private static final String PAYMENT_KEY_PREFIX = "payment:";

    private final PaymentResultApplier applier;

    public EntryRejectedEventHandler(PaymentResultApplier applier) {
        this.applier = applier;
    }

    @Override
    public void handle(EventEnvelope<EntryRejectedV1> envelope) {
        EntryRejectedV1 event = envelope.payload();
        String key = event.idempotencyKey();
        if (key == null || !key.startsWith(PAYMENT_KEY_PREFIX)) {
            return;
        }

        UUID paymentId;
        try {
            paymentId = UUID.fromString(key.substring(PAYMENT_KEY_PREFIX.length()));
        } catch (IllegalArgumentException notAUuid) {
            log.warn("rejection with key \"{}\" claims the payment: prefix but no payment id follows it", key);
            return;
        }
        if (event.referenceId() != null && !event.referenceId().equals(paymentId.toString())) {
            log.warn("rejection for payment {} carries a contradictory referenceId \"{}\" - trusting the key",
                    paymentId, event.referenceId());
        }

        applier.fail(paymentId, event.reason());
    }
}
