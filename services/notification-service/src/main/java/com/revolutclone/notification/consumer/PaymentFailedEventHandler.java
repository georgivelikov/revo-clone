package com.revolutclone.notification.consumer;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.PaymentFailedV1;
import com.revolutclone.messaging.api.EventHandler;
import com.revolutclone.notification.domain.Notification;
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
 * A failed transfer is the one saga outcome that <strong>is</strong> worth a persisted feed row. No
 * money moved, so no ledger posting will ever produce one, and the reason - {@code INSUFFICIENT_FUNDS}
 * and the rest - is exactly what the user needs to see and to be able to find again later. The
 * optimistic row in the client is rolled back by this same frame.
 *
 * <p>Sender only: the receiver was never told the transfer existed (see
 * {@link PaymentInitiatedEventHandler}), and telling them now that something they never knew about
 * has failed would be worse than silence.
 */
@Component
public class PaymentFailedEventHandler implements EventHandler<PaymentFailedV1> {

    private static final Logger log = LoggerFactory.getLogger(PaymentFailedEventHandler.class);

    private final PaymentPartiesRepository paymentParties;
    private final AccountOwnerRepository accountOwners;
    private final NotificationFanOut fanOut;

    public PaymentFailedEventHandler(PaymentPartiesRepository paymentParties, AccountOwnerRepository accountOwners,
            NotificationFanOut fanOut) {
        this.paymentParties = paymentParties;
        this.accountOwners = accountOwners;
        this.fanOut = fanOut;
    }

    @Override
    @Transactional
    public void handle(EventEnvelope<PaymentFailedV1> envelope) {
        PaymentFailedV1 event = envelope.payload();
        Optional<PaymentParties> parties = paymentParties.findById(event.paymentId());
        if (parties.isEmpty()) {
            log.warn("payment {} failed but its parties were never projected - no notification", event.paymentId());
            return;
        }

        PaymentParties payment = parties.get();
        Optional<UUID> senderUserId = accountOwners.findById(payment.getSenderAccountId())
                .map(owner -> owner.getUserId());
        if (senderUserId.isEmpty()) {
            log.warn("payment {} failed but account {} has no owner in the read model", event.paymentId(),
                    payment.getSenderAccountId());
            return;
        }

        fanOut.record(Notification.transferFailed(
                senderUserId.get(),
                payment.getPaymentId(),
                payment.getAmountMinor(),
                payment.getCurrency(),
                "Transfer failed",
                event.reason(),
                envelope.correlationId(),
                envelope.occurredAt()), null);
    }
}
