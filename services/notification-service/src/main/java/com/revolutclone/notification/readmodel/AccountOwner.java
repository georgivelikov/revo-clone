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
 * Which user owns an account-service account - projected from the compacted
 * {@code account.account.v1}, never fetched over HTTP (CLAUDE.md rule 7, and the phase's "no
 * sideways HTTP" rule). This is the last hop of every fan-out: every event this service consumes
 * names an account, and a socket is addressed by user.
 *
 * <p>Keyed on the account id, which is also the Kafka message key on that topic, so the same
 * account's events always arrive in publication order on the same partition and applying them in
 * order always lands on the same row. That is what makes the projection safe to rebuild from
 * offset zero on every partition assignment - see {@code AccountKafkaListener}.
 */
@Entity
@Table(name = "account_owner")
public class AccountOwner implements Persistable<UUID> {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

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
    public void apply(UUID userId, String currency, Instant occurredAt) {
        this.userId = userId;
        this.currency = currency;
        this.updatedAt = occurredAt;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
