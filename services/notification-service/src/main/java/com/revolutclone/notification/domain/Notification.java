package com.revolutclone.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * One row of a user's feed - the persisted half of "pushes over STOMP ... persists notifications
 * so the UI can show a history and an unread badge" from plans/phase-03-payments-and-ui.md.
 *
 * <p>{@code createdAt} is the {@code occurredAt} of the event that caused this notification, never
 * {@code now()}. The feed is ordered by when the money moved, not by when this consumer happened
 * to catch up: a consumer that was down for ten minutes must not reorder ten minutes of a user's
 * history when it restarts.
 *
 * <p>{@code amountMinor} is signed minor units exactly as {@code EntryPostedV1} carries it -
 * negative for a DEBIT, positive for a CREDIT - so the UI adds them up without needing to know the
 * accounting convention. {@code long} plus a currency code, per CLAUDE.md rule 1; {@code Long}
 * rather than {@code long} only because a {@link NotificationType#PUSH} has no amount at all.
 *
 * <p>Implements {@link Persistable} with a {@code @Transient} new-flag for the same reason
 * {@code payment-service}'s entities do: the id is assigned rather than generated, so Spring
 * Data's default heuristic would route the first {@code save()} through {@code merge()} and cost
 * a pointless select, while an always-new flag would break the mark-as-read update path.
 */
@Entity
@Table(name = "notification")
public class Notification implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    @Column(nullable = false)
    private String title;

    @Column
    private String body;

    /** Set when this notification is about a transfer, so the UI can link the two. */
    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "amount_minor")
    private Long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 3, columnDefinition = "char(3)")
    private String currency;

    /** The causing event's correlation id, so a push is traceable end to end. */
    @Column(name = "correlation_id")
    private String correlationId;

    /** Null means unread, which is the whole badge query. */
    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected Notification() {
        // JPA
    }

    private Notification(UUID id, UUID userId, NotificationType type, String title, String body, UUID paymentId,
            Long amountMinor, String currency, String correlationId, Instant occurredAt) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.body = body;
        this.paymentId = paymentId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.correlationId = correlationId;
        this.createdAt = occurredAt;
    }

    /** A money movement the ledger confirmed, from one {@code EntryPostedV1} posting. */
    public static Notification movement(UUID userId, long signedAmountMinor, String currency, UUID paymentId,
            String title, String body, String correlationId, Instant occurredAt) {
        NotificationType type = signedAmountMinor < 0 ? NotificationType.MONEY_OUT : NotificationType.MONEY_IN;
        return new Notification(UUID.randomUUID(), userId, type, title, body, paymentId, signedAmountMinor, currency,
                correlationId, occurredAt);
    }

    /** A transfer the ledger refused, kept because the reason is the whole point of the row. */
    public static Notification transferFailed(UUID userId, UUID paymentId, long amountMinor, String currency,
            String title, String reason, String correlationId, Instant occurredAt) {
        return new Notification(UUID.randomUUID(), userId, NotificationType.TRANSFER_FAILED, title, reason, paymentId,
                -amountMinor, currency, correlationId, occurredAt);
    }

    /** A {@code NotificationPushV1} - the producer already decided what the user should be told. */
    public static Notification push(UUID userId, String title, String body, String correlationId, Instant occurredAt) {
        return new Notification(UUID.randomUUID(), userId, NotificationType.PUSH, title, body, null, null, null,
                correlationId, occurredAt);
    }

    /**
     * Idempotent on purpose: marking an already-read notification read again is not an error, and
     * keeping the first timestamp means the badge cannot be made to flicker by a retried click.
     */
    public void markRead(Instant readAt) {
        if (this.readAt == null) {
            this.readAt = readAt;
        }
    }

    public boolean isUnread() {
        return readAt == null;
    }

    @Override
    @Transient
    public UUID getId() {
        return id;
    }

    @Override
    @Transient
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public UUID getUserId() {
        return userId;
    }

    public NotificationType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public Long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
