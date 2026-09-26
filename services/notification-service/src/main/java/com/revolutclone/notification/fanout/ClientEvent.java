package com.revolutclone.notification.fanout;

import com.revolutclone.notification.domain.Notification;
import java.time.Instant;
import java.util.UUID;

/**
 * One frame on {@code /user/{userId}/queue/events} - the only shape the browser has to understand.
 *
 * <p>Two kinds of frame travel over this queue, and the difference is deliberate:
 * <ul>
 *   <li><strong>Feed frames</strong> ({@code MONEY_IN}, {@code MONEY_OUT}, {@code TRANSFER_FAILED},
 *       {@code PUSH}) mirror a persisted {@link Notification} and carry its {@code notificationId},
 *       so the UI can mark it read and find it again in {@code GET /api/notifications}.</li>
 *   <li><strong>Status frames</strong> ({@code PAYMENT_PENDING}, {@code PAYMENT_COMPLETED}) carry no
 *       {@code notificationId} because nothing is persisted for them. They exist for the optimistic
 *       UI: the client inserted a local PENDING row when it POSTed, and this is what flips it to
 *       settled. A transfer's lasting feed entry comes from the ledger postings instead - one per
 *       party, each with that party's own balance - so persisting the status transition too would
 *       show the same transfer twice.</li>
 * </ul>
 *
 * <p>{@code correlationId} is on every frame on purpose: it is the same id that flows through the
 * gateway, payment-service, the ledger and this service's logs, which is what makes the dev drawer
 * (task 03.11) able to line a socket frame up against the request that caused it.
 *
 * <p>{@code amountMinor} is signed minor units plus a currency code - CLAUDE.md rule 1 - never a
 * fractional type. The UI formats it; the wire never carries a decimal.
 */
public record ClientEvent(
        String type,
        Instant occurredAt,
        String correlationId,
        UUID notificationId,
        UUID paymentId,
        String title,
        String body,
        Long amountMinor,
        String currency,
        Long balanceAfterMinor,
        String status,
        String reason) {

    /** A transfer has been accepted but not settled - the honest state behind the API's 202. */
    public static final String PAYMENT_PENDING = "PAYMENT_PENDING";

    /** The ledger posted the entry; both parties' balances have moved. */
    public static final String PAYMENT_COMPLETED = "PAYMENT_COMPLETED";

    /** Mirrors a persisted feed row, including the id the UI needs to mark it read. */
    public static ClientEvent of(Notification notification, Long balanceAfterMinor) {
        return new ClientEvent(
                notification.getType().name(),
                notification.getCreatedAt(),
                notification.getCorrelationId(),
                notification.getId(),
                notification.getPaymentId(),
                notification.getTitle(),
                notification.getBody(),
                notification.getAmountMinor(),
                notification.getCurrency(),
                balanceAfterMinor,
                null,
                null);
    }

    /** The saga's first visible step, sent to the sender only: nobody else knows yet. */
    public static ClientEvent paymentPending(UUID paymentId, long amountMinor, String currency, String reference,
            Instant occurredAt, String correlationId) {
        return new ClientEvent(PAYMENT_PENDING, occurredAt, correlationId, null, paymentId, "Payment sent", reference,
                -amountMinor, currency, null, "PENDING", null);
    }

    /** The saga's terminal success, sent to both parties. */
    public static ClientEvent paymentCompleted(UUID paymentId, long signedAmountMinor, String currency,
            Instant occurredAt, String correlationId) {
        return new ClientEvent(PAYMENT_COMPLETED, occurredAt, correlationId, null, paymentId, "Payment completed", null,
                signedAmountMinor, currency, null, "COMPLETED", null);
    }
}
