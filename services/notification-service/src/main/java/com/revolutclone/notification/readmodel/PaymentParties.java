package com.revolutclone.notification.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Who the two parties to a payment are, projected from {@code PaymentInitiatedV1}.
 *
 * <p>{@code PaymentCompletedV1} and {@code PaymentFailedV1} carry only a {@code paymentId} - by
 * design, since payment-service already told the world the details once - so without this row
 * there is no way to answer "whose screens does this belong on", and step 4 of the phase's saga
 * asks the fan-out to reach <em>both</em> parties.
 */
@Entity
@Table(name = "payment_parties")
public class PaymentParties implements Persistable<UUID> {

    @Id
    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "sender_account_id", nullable = false)
    private UUID senderAccountId;

    @Column(name = "receiver_account_id", nullable = false)
    private UUID receiverAccountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    /**
     * The sender's note. Always null today: {@code PaymentInitiatedV1} does not carry the
     * reference, because ledger-service - its only other consumer - has no use for it. The column
     * exists so that adding the field to the event is a one-line change here rather than a
     * migration, and because a notification without the user's own note is the obvious next thing
     * the UI will ask for.
     */
    @Column
    private String reference;

    /** {@code occurredAt} of {@code PaymentInitiatedV1}. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected PaymentParties() {
        // JPA
    }

    public PaymentParties(UUID paymentId) {
        this.paymentId = paymentId;
    }

    public void apply(UUID senderAccountId, UUID receiverAccountId, long amountMinor, String currency,
            String reference, Instant occurredAt) {
        this.senderAccountId = senderAccountId;
        this.receiverAccountId = receiverAccountId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.reference = reference;
        this.createdAt = occurredAt;
    }

    @Override
    @Transient
    public UUID getId() {
        return paymentId;
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

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getSenderAccountId() {
        return senderAccountId;
    }

    public UUID getReceiverAccountId() {
        return receiverAccountId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getReference() {
        return reference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
