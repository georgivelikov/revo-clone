package com.revolutclone.fx.readmodel;

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
 * Who owns an account-service account and what currency it holds - projected from the compacted
 * {@code account.account.v1}, never fetched over HTTP (CLAUDE.md rule 7). A quote request names
 * only currencies, so this is the first half of turning "the caller wants to sell EUR" into a
 * ledger account a hold can be placed on; {@link LedgerAccountRef} is the second.
 *
 * <p>Keyed on the account id, which is also the Kafka message key on that topic, so the same
 * account's events always arrive in publication order on the same partition and applying them in
 * order always lands on the same row. That is what makes the projection safe to rebuild from
 * offset zero on every partition assignment - see {@code AccountKafkaListener}.
 */
@Entity
@Table(name = "account_owner")
public class AccountOwner implements Persistable<UUID> {

    /** The only {@code type} an exchange ever sells out of - a vault is not a spending balance. */
    public static final String WALLET = "WALLET";

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    /**
     * Free text rather than an enum, following payment-service's {@code AccountView}: fx-service
     * only ever compares it to {@link #WALLET}, and a projection that threw on a type its upstream
     * invented later would stall the consumer over a value it does not read.
     */
    @Column(nullable = false)
    private String type;

    /** {@code occurredAt} of the last event applied here - event time, never wall clock. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    protected AccountOwner() {
        // JPA
    }

    public AccountOwner(UUID accountId) {
        this.accountId = accountId;
    }

    /**
     * Every field is overwritten from the event, which is what makes the projection converge on a
     * replay: applying the same event twice leaves exactly the same row.
     */
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
