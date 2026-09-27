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
import org.springframework.data.domain.Persistable;

/**
 * Projection of {@code LedgerAccountCreatedV1} on {@code ledger.entry.v1}: which account-service
 * account a ledger account belongs to.
 *
 * <p>The ledger's reservation API is addressed by <em>ledger</em> account id, and nothing on
 * {@code account.account.v1} carries that - ledger-service publishes this event precisely so the
 * mapping is available to whoever needs it. Same shape as fx-service's own projection of the same
 * event; each service owns its copy rather than one reading another's (CLAUDE.md rule 7).
 */
@Entity
@Table(name = "ledger_account_ref")
public class LedgerAccountRef implements Persistable<UUID> {

    @Id
    @Column(name = "ledger_account_id")
    private UUID ledgerAccountId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** {@code occurredAt} of the last event applied here - event time, never wall clock. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    protected LedgerAccountRef() {
        // JPA
    }

    public LedgerAccountRef(UUID ledgerAccountId) {
        this.ledgerAccountId = ledgerAccountId;
    }

    public void apply(UUID accountId, Instant occurredAt) {
        this.accountId = accountId;
        this.updatedAt = occurredAt;
    }

    @Override
    @Transient
    public UUID getId() {
        return ledgerAccountId;
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

    public UUID getLedgerAccountId() {
        return ledgerAccountId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
