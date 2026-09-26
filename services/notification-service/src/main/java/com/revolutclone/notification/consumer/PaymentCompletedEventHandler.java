package com.revolutclone.notification.consumer;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.PaymentCompletedV1;
import com.revolutclone.messaging.api.EventHandler;
import com.revolutclone.notification.fanout.ClientEvent;
import com.revolutclone.notification.fanout.NotificationFanOut;
import com.revolutclone.notification.readmodel.AccountOwnerRepository;
import com.revolutclone.notification.readmodel.PaymentParties;
import com.revolutclone.notification.readmodel.PaymentPartiesRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles the optimistic row on both sides. {@code PaymentCompletedV1} carries only the payment id -
 * which is why {@link PaymentParties} exists - and the frame is transient, because the lasting feed
 * entry is the ledger posting {@link EntryPostedEventHandler} turns into a MONEY_IN/MONEY_OUT row.
 *
 * <p>The amount is signed per party: the sender sees it leave, the receiver sees it arrive. Same
 * convention as the ledger's own events, so the UI never has to ask which side of a transfer it is
 * looking at.
 *
 * <p>An unknown payment id is logged rather than thrown. It means this service started consuming
 * {@code payment.event.v1} after the transfer began - a genuinely possible ordering for a service
 * deployed mid-flight - and throwing would retry, then dead-letter, a message that can never
 * succeed, while the UI's polling fallback already covers the user.
 */
@Component
public class PaymentCompletedEventHandler implements EventHandler<PaymentCompletedV1> {

    private static final Logger log = LoggerFactory.getLogger(PaymentCompletedEventHandler.class);

    private final PaymentPartiesRepository paymentParties;
    private final AccountOwnerRepository accountOwners;
    private final NotificationFanOut fanOut;

    public PaymentCompletedEventHandler(PaymentPartiesRepository paymentParties,
            AccountOwnerRepository accountOwners, NotificationFanOut fanOut) {
        this.paymentParties = paymentParties;
        this.accountOwners = accountOwners;
        this.fanOut = fanOut;
    }

    @Override
    @Transactional
    public void handle(EventEnvelope<PaymentCompletedV1> envelope) {
        UUID paymentId = envelope.payload().paymentId();
        Optional<PaymentParties> parties = paymentParties.findById(paymentId);
        if (parties.isEmpty()) {
            log.warn("payment {} completed but its parties were never projected - no push", paymentId);
            return;
        }

        PaymentParties payment = parties.get();
        pushTo(payment.getSenderAccountId(), -payment.getAmountMinor(), payment, envelope);
        pushTo(payment.getReceiverAccountId(), payment.getAmountMinor(), payment, envelope);
    }

    private void pushTo(UUID accountId, long signedAmountMinor, PaymentParties payment,
            EventEnvelope<PaymentCompletedV1> envelope) {
        accountOwners.findById(accountId).ifPresent(owner -> fanOut.push(owner.getUserId(),
                ClientEvent.paymentCompleted(payment.getPaymentId(), signedAmountMinor, payment.getCurrency(),
                        envelope.occurredAt(), envelope.correlationId())));
    }
}
