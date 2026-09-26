package com.revolutclone.notification.consumer;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.PaymentInitiatedV1;
import com.revolutclone.messaging.api.EventHandler;
import com.revolutclone.notification.fanout.ClientEvent;
import com.revolutclone.notification.readmodel.AccountOwnerRepository;
import com.revolutclone.notification.readmodel.PaymentParties;
import com.revolutclone.notification.readmodel.PaymentPartiesRepository;
import com.revolutclone.notification.fanout.NotificationFanOut;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Does two things, both necessary. It records who the payment's two parties are - the only event in
 * the saga that says so - and it tells the sender their transfer was accepted.
 *
 * <p>The pending frame is not persisted, and that is the point of the optimistic UI the phase
 * describes: the client already inserted a local PENDING row when it POSTed, so this frame only has
 * to confirm the server agrees. A feed row would be a duplicate of something the ledger is about to
 * make real, and would have to be deleted again if the transfer failed.
 *
 * <p>Only the sender hears about it. The receiver has no reason to see a transfer that may still be
 * rejected for insufficient funds; they find out when the money arrives.
 */
@Component
public class PaymentInitiatedEventHandler implements EventHandler<PaymentInitiatedV1> {

    private static final Logger log = LoggerFactory.getLogger(PaymentInitiatedEventHandler.class);

    private final PaymentPartiesRepository paymentParties;
    private final AccountOwnerRepository accountOwners;
    private final NotificationFanOut fanOut;

    public PaymentInitiatedEventHandler(PaymentPartiesRepository paymentParties,
            AccountOwnerRepository accountOwners, NotificationFanOut fanOut) {
        this.paymentParties = paymentParties;
        this.accountOwners = accountOwners;
        this.fanOut = fanOut;
    }

    @Override
    @Transactional
    public void handle(EventEnvelope<PaymentInitiatedV1> envelope) {
        PaymentInitiatedV1 event = envelope.payload();

        PaymentParties parties = paymentParties.findById(event.paymentId())
                .orElseGet(() -> new PaymentParties(event.paymentId()));
        // reference is null: PaymentInitiatedV1 does not carry the sender's note - see PaymentParties.
        parties.apply(event.senderAccountId(), event.receiverAccountId(), event.amountMinor(), event.currency(), null,
                envelope.occurredAt());
        paymentParties.saveAndFlush(parties);

        accountOwners.findById(event.senderAccountId()).ifPresentOrElse(
                sender -> fanOut.push(sender.getUserId(), ClientEvent.paymentPending(event.paymentId(),
                        event.amountMinor(), event.currency(), parties.getReference(), envelope.occurredAt(),
                        envelope.correlationId())),
                () -> logUnknownAccount(event.senderAccountId(), event.paymentId()));
    }

    private static void logUnknownAccount(UUID accountId, UUID paymentId) {
        log.warn("payment {} was sent from account {}, which this service's read model does not know yet", paymentId,
                accountId);
    }
}
