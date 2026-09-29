package com.revolutclone.analytics.payment;

import java.util.UUID;

/** The one fact every caller of {@link PaymentInitiationClient} needs back. */
public record InitiatedPayment(UUID paymentId, String status) {
}
