package com.revolutclone.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Mirrors plans/phase-03-payments-and-ui.md's {@code payment} table exactly, and owns the
 * state machine in {@link PaymentStatus}. Money is {@code long} minor units plus an ISO
 * currency code, never a decimal type.
 *
 * <p>All four state changes go through the single private {@link #transitionTo} method, which
 * consults {@link PaymentStatus#canTransitionTo} and throws
 * {@link IllegalPaymentTransitionException} when the move is illegal. The phase spec calls
 * this out explicitly - "an illegal transition throws rather than silently updating" - and the
 * reason is that this entity is mutated by a Kafka consumer reacting to the ledger's result.
 * A silent no-op there turns a redelivered-or-contradictory event into a payment that quietly
 * disagrees with the ledger, which is exactly the class of bug the whole double-entry design
 * exists to make impossible.
 *
 * <p>Implements {@link Persistable} with a {@code @Transient} new-flag rather than
 * {@code Account}'s always-new approach: unlike an account, a payment is inserted once
 * (PENDING) and then <em>updated</em> when the ledger result arrives, so always-new would
 * route that update through {@code persist()} and fail on the primary key. The flag gives
 * {@code persist()} on the insert - which is what makes {@code @PrePersist} populate
 * {@code createdAt} on the caller's own instance, per the note on {@code Account} - and
 * {@code merge()} thereafter. Transitions driven by a consumer should still load a managed
 * instance and mutate it in place, letting dirty checking and {@code @PreUpdate} do the write.
 */
@Entity
@Table(name = "payment")
public class Payment implements Persistable<UUID> {

    @Id
    private UUID id;

    /**
     * The client's {@code Idempotency-Key} header, scoped per initiator by
     * {@code unique (initiator_user_id, idempotency_key)}. Deliberately never forwarded to the
     * ledger: {@code journal_entry.idempotency_key} is <em>globally</em> unique, so two users
     * choosing the same key would collide there and one would silently receive the other's
     * journal entry. The ledger key is derived from the payment id instead - see
     * {@link #ledgerIdempotencyKey()}.
     */
    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "initiator_user_id", nullable = false)
    private UUID initiatorUserId;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "target_account_id", nullable = false)
    private UUID targetAccountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    /** The user's note. Free text, never interpreted. */
    @Column(name = "reference")
    private String reference;

    /** The ledger's journal entry id, known only once the entry posts. */
    @Column(name = "ledger_entry_id")
    private UUID ledgerEntryId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    protected Payment() {
        // JPA
    }

    public Payment(UUID id, String idempotencyKey, UUID initiatorUserId, UUID sourceAccountId,
            UUID targetAccountId, long amountMinor, String currency, String reference) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be > 0, got " + amountMinor);
        }
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.initiatorUserId = initiatorUserId;
        this.sourceAccountId = sourceAccountId;
        this.targetAccountId = targetAccountId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.reference = reference;
        this.status = PaymentStatus.PENDING;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        // The column is `not null` with no default, so it has to be populated on insert too,
        // not only on update.
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * The ledger result said the entry posted. {@code ledgerEntryId} is recorded so the UI's
     * activity-detail screen can show the journal entry behind the payment.
     */
    public void markCompleted(UUID ledgerEntryId) {
        transitionTo(PaymentStatus.COMPLETED);
        this.ledgerEntryId = ledgerEntryId;
    }

    /** The ledger rejected the entry, or the payment failed after acceptance. */
    public void markFailed(String failureReason) {
        transitionTo(PaymentStatus.FAILED);
        this.failureReason = failureReason;
    }

    /** Withdrawn before the ledger settled it. */
    public void markCancelled() {
        transitionTo(PaymentStatus.CANCELLED);
    }

    private void transitionTo(PaymentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalPaymentTransitionException(id, status, target);
        }
        this.status = target;
    }

    /**
     * The key this payment presents to the ledger, never the client's header - see the note on
     * {@link #idempotencyKey}. Deriving it from the payment id makes it globally unique by
     * construction, which is what {@code journal_entry.idempotency_key} requires.
     */
    public String ledgerIdempotencyKey() {
        return "payment:" + id;
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

    @Override
    @Transient
    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getInitiatorUserId() {
        return initiatorUserId;
    }

    public UUID getSourceAccountId() {
        return sourceAccountId;
    }

    public UUID getTargetAccountId() {
        return targetAccountId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getReference() {
        return reference;
    }

    public UUID getLedgerEntryId() {
        return ledgerEntryId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
