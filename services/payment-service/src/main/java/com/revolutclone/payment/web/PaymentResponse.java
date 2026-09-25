package com.revolutclone.payment.web;

import com.revolutclone.payment.domain.Payment;
import com.revolutclone.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * The phase spec's minimum for the 202 is {@code { paymentId, status: PENDING }}; everything
 * beyond that is the client's own request echoed back plus the two fields that only become
 * non-null once the ledger's result arrives. Sending the whole thing means the same shape can
 * serve the poll fallback and the activity feed later, and it means the gateway's replayed
 * response for a duplicate key is still a complete answer.
 *
 * <p>{@code failureReason} and {@code ledgerEntryId} are serialized even when null, so the UI
 * can tell "PENDING, no reason yet" from a field that was dropped.
 */
public record PaymentResponse(
        UUID paymentId,
        PaymentStatus status,
        UUID sourceAccountId,
        UUID targetAccountId,
        long amountMinor,
        String currency,
        String reference,
        String failureReason,
        UUID ledgerEntryId,
        Instant createdAt,
        Instant updatedAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getStatus(),
                payment.getSourceAccountId(),
                payment.getTargetAccountId(),
                payment.getAmountMinor(),
                payment.getCurrency(),
                payment.getReference(),
                payment.getFailureReason(),
                payment.getLedgerEntryId(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }
}
