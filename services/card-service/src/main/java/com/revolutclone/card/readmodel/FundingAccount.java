package com.revolutclone.card.readmodel;

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
 * Projection of {@code AccountOpenedV1} on the compacted {@code account.account.v1}: what currency
 * a card's funding wallet holds, and who owns it.
 *
 * <p>Needed because an authorization arrives in the merchant's currency and has to be held in the
 * wallet's. Not a source of truth - see V5__card_funding_read_model.sql.
 */
@Entity
@Table(name = "funding_account")
public class FundingAccount implements Persistable<UUID> {

    /** {@code type} as account-service spells it; only a wallet can fund a card. */
    public static final String WALLET = "WALLET";

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Column(nullable = false)
    private String type;

    /** {@code occurredAt} of the last event applied here - event time, never wall clock. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    protected FundingAccount() {
        // JPA
    }

    public FundingAccount(UUID accountId) {
        this.accountId = accountId;
    }

    public void apply(UUID userId, String currency, String type, Instant occurredAt) {
        this.userId = userId;
        this.currency = currency;
        this.type = type;
        this.updatedAt = occurredAt;
    }

    public boolean isWallet() {
        return WALLET.equals(type);
    }

    @Override
    @Transient
    public UUID getId() {
        return accountId;
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

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getCurrency() {
        return currency;
    }

    public String getType() {
        return type;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
