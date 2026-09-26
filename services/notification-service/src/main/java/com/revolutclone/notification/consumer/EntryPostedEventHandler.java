package com.revolutclone.notification.consumer;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.EntryPostedV1;
import com.revolutclone.messaging.api.EventHandler;
import com.revolutclone.notification.domain.Notification;
import com.revolutclone.notification.fanout.NotificationFanOut;
import com.revolutclone.notification.readmodel.AccountOwner;
import com.revolutclone.notification.readmodel.AccountOwnerRepository;
import com.revolutclone.notification.readmodel.LedgerAccountRef;
import com.revolutclone.notification.readmodel.LedgerAccountRefRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The money-movement half of the fan-out: one confirmed posting becomes one feed entry for whoever
 * owns the account it moved. This is what makes a transfer visible to <em>both</em> parties, as the
 * phase's saga step 4 requires - the ledger emits one {@link EntryPostedV1} per posting, so a
 * transfer produces a MONEY_OUT for the sender and a MONEY_IN for the receiver, each carrying that
 * party's own balance.
 *
 * <p>Preferring the ledger event over {@code PaymentCompletedV1} for the feed entry is deliberate.
 * The ledger event is the one that knows the money actually moved, and it carries
 * {@code balanceAfterMinor}, so the UI can update a balance without a follow-up request. It also
 * covers movements that are not payments at all - funding, and later fees, FX and card settlements -
 * with no extra code here.
 *
 * <p><strong>Two lookups, and a miss on either is not an error.</strong> {@code ledger.entry.v1}
 * carries every posting in the system, including the system accounts
 * ({@code EXTERNAL_CLEARING}, {@code FX_POOL}, {@code FEE_INCOME}) that are seeded by a ledger
 * migration and belong to no user - they have no {@link LedgerAccountRef} by construction, and
 * skipping them silently is correct rather than defensive. A ledger account that maps to an account
 * this service has not yet projected is the genuinely interesting case and is logged: it means the
 * {@code account.account.v1} projection is behind, and the next partition assignment's replay will
 * fix the mapping - though not this notification, which is one of the honest costs of a read model.
 */
@Component
public class EntryPostedEventHandler implements EventHandler<EntryPostedV1> {

    private static final Logger log = LoggerFactory.getLogger(EntryPostedEventHandler.class);

    private static final String TRANSFER = "TRANSFER";

    private final LedgerAccountRefRepository ledgerAccountRefs;
    private final AccountOwnerRepository accountOwners;
    private final NotificationFanOut fanOut;

    public EntryPostedEventHandler(LedgerAccountRefRepository ledgerAccountRefs,
            AccountOwnerRepository accountOwners, NotificationFanOut fanOut) {
        this.ledgerAccountRefs = ledgerAccountRefs;
        this.accountOwners = accountOwners;
        this.fanOut = fanOut;
    }

    @Override
    @Transactional
    public void handle(EventEnvelope<EntryPostedV1> envelope) {
        EntryPostedV1 event = envelope.payload();

        Optional<LedgerAccountRef> ref = ledgerAccountRefs.findById(event.accountId());
        if (ref.isEmpty()) {
            log.debug("ledger account {} belongs to no user wallet - nothing to notify", event.accountId());
            return;
        }
        UUID accountId = ref.get().getAccountId();

        Optional<AccountOwner> owner = accountOwners.findById(accountId);
        if (owner.isEmpty()) {
            log.warn("account {} has a ledger account but no owner in the read model - dropping the notification "
                    + "for entry {}", accountId, event.entryId());
            return;
        }

        fanOut.record(Notification.movement(
                owner.get().getUserId(),
                event.amountMinor(),
                event.currency(),
                paymentIdOf(event),
                titleFor(event.amountMinor()),
                bodyFor(event.type()),
                envelope.correlationId(),
                envelope.occurredAt()), event.balanceAfterMinor());
    }

    /**
     * Only a {@code TRANSFER} entry's {@code referenceId} is a payment id - that is what the ledger's
     * own consumer sets it to. Every other entry type uses the column for its own reference, so
     * reading one as a payment id would link a card settlement to a payment that does not exist.
     */
    private static UUID paymentIdOf(EntryPostedV1 event) {
        if (!TRANSFER.equals(event.type()) || event.referenceId() == null || event.referenceId().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(event.referenceId());
        } catch (IllegalArgumentException notAUuid) {
            log.warn("TRANSFER entry {} has referenceId \"{}\", which is not a payment id", event.entryId(),
                    event.referenceId());
            return null;
        }
    }

    /**
     * {@code EntryPostedV1.amountMinor} is signed per the posting direction - negative for a DEBIT,
     * which on a user wallet means the customer's money decreased (see {@code PostingEngine}'s sign
     * convention). Amounts are never formatted into these strings: money is minor units plus a
     * currency code on the wire, and the UI decides how to render it.
     */
    private static String titleFor(long signedAmountMinor) {
        return signedAmountMinor < 0 ? "Money out" : "Money in";
    }

    private static String bodyFor(String entryType) {
        return switch (entryType) {
            case TRANSFER -> "Transfer";
            case "ADJUSTMENT" -> "Account funded";
            default -> entryType;
        };
    }
}
