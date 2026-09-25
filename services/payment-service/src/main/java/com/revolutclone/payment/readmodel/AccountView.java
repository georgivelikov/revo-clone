package com.revolutclone.payment.readmodel;

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
 * One row of payment-service's local account read model - its own copy of the account facts
 * {@code POST /api/payments} validates against, projected from {@code account.account.v1}. See
 * {@code V4__account_view.sql} for why this exists rather than an HTTP call to
 * {@code account-service}.
 *
 * <p>The identifier is account-service's own {@code accountId}, which is also the Kafka message
 * key on that topic. That is what makes this projection safe to rebuild: the same account's
 * events always land on the same partition in publication order, and applying them in order
 * always lands on the same row, whether it is the first delivery or the thousandth replay.
 * There is no version column and no optimistic locking - a projection has no conflicting
 * writers to detect, only a log to re-read.
 *
 * <p>Implements {@link Persistable} with a {@code @Transient} new-flag for the same reason
 * {@code Payment} does: the id is assigned rather than generated, so Spring Data's default
 * heuristic would route the very first {@code save()} through {@code merge()} and cost a
 * pointless select, while an always-new flag (the shape {@code Account} uses) would break the
 * update path this entity is built around.
 */
@Entity
@Table(name = "account_view")
public class AccountView implements Persistable<UUID> {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    /**
     * Kept as free text rather than an enum, unlike {@link #state}: payment-service does not
     * branch on the account type, and a projection that throws on a type its upstream invented
     * later would stall the consumer over a field it never reads.
     */
    @Column(nullable = false)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountViewState state;

    /** {@code occurredAt} of the last event applied here - event time, never wall clock. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    protected AccountView() {
        // JPA
    }

    public AccountView(UUID accountId) {
        this.accountId = accountId;
    }

    /**
     * Applies an event's account facts to this row. Every field is overwritten from the event,
     * which is what makes the projection converge on a replay: applying the same event twice
     * leaves exactly the same row, and applying a later event wins because Kafka delivers the
     * same key in order.
     */
    public void apply(UUID userId, String currency, String type, AccountViewState state, Instant occurredAt) {
        this.userId = userId;
        this.currency = currency;
        this.type = type;
        this.state = state;
        this.updatedAt = occurredAt;
    }

    public boolean isActive() {
        return state == AccountViewState.ACTIVE;
    }

    public boolean isOwnedBy(UUID candidateUserId) {
        return userId.equals(candidateUserId);
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

    public AccountViewState getState() {
        return state;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
