package com.revolutclone.card.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Mirrors plans/phase-05-cards.md's {@code card} table exactly. {@code panHash} is the only
 * lookup key the authorization flow ever uses - never {@code panLast4}, which is display-only
 * and, by design, not unique (two real cards can share last4 and expiry; see
 * CardLookupByPanHashTest). {@code panEncrypted}/{@code cvvEncrypted} are AES-GCM ciphertext
 * (see PanCipher) and are never decrypted except behind the one step-up-gated reveal endpoint
 * a later task adds.
 *
 * <p>Implements {@link Persistable} and always reports {@code isNew() == true} - same reasoning
 * as {@code account-service}'s {@code Account}: with a manually-assigned (non-generated) id,
 * Spring Data's default heuristic would otherwise route {@code save()} through JPA
 * {@code merge()} instead of {@code persist()}, silently skipping {@code @PrePersist}.
 */
@Entity
@Table(name = "card")
public class Card implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "pan_hash", nullable = false)
    private byte[] panHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "pan_last4", nullable = false, length = 4, columnDefinition = "char(4)")
    private String panLast4;

    @Column(name = "pan_encrypted", nullable = false)
    private byte[] panEncrypted;

    @Column(name = "expiry_month", nullable = false)
    private int expiryMonth;

    @Column(name = "expiry_year", nullable = false)
    private int expiryYear;

    @Column(name = "cvv_encrypted", nullable = false)
    private byte[] cvvEncrypted;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CardType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CardState state;

    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Card() {
        // JPA
    }

    public Card(
            UUID id,
            UUID userId,
            UUID accountId,
            byte[] panHash,
            String panLast4,
            byte[] panEncrypted,
            int expiryMonth,
            int expiryYear,
            byte[] cvvEncrypted,
            CardType type,
            CardState state,
            String label) {
        this.id = id;
        this.userId = userId;
        this.accountId = accountId;
        this.panHash = panHash;
        this.panLast4 = panLast4;
        this.panEncrypted = panEncrypted;
        this.expiryMonth = expiryMonth;
        this.expiryYear = expiryYear;
        this.cvvEncrypted = cvvEncrypted;
        this.type = type;
        this.state = state;
        this.label = label;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    @Override
    @Transient
    public boolean isNew() {
        return true;
    }

    @Override
    @Transient
    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public byte[] getPanHash() {
        return panHash;
    }

    public String getPanLast4() {
        return panLast4;
    }

    public byte[] getPanEncrypted() {
        return panEncrypted;
    }

    public int getExpiryMonth() {
        return expiryMonth;
    }

    public int getExpiryYear() {
        return expiryYear;
    }

    public byte[] getCvvEncrypted() {
        return cvvEncrypted;
    }

    public CardType getType() {
        return type;
    }

    public CardState getState() {
        return state;
    }

    public String getLabel() {
        return label;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
