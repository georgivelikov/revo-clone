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
import org.springframework.data.domain.Persistable;

/**
 * The ledger account id to account-service account id correspondence, projected from
 * {@code LedgerAccountCreatedV1} on {@code ledger.entry.v1}.
 *
 * <p>Needed because {@code EntryPostedV1} - the event that actually says money moved - is keyed by
 * and names the <em>ledger</em> account, while {@link AccountOwner} (and therefore the user whose
 * socket this belongs on) is keyed by the account-service account. This row is the only hop
 * between the two, and ledger-service already publishes it for exactly this reason: it is what
 * account-service uses to fill its own {@code ledger_account_id}.
 */
@Entity
@Table(name = "ledger_account_ref")
public class LedgerAccountRef implements Persistable<UUID> {

    @Id
    @Column(name = "ledger_account_id")
    private UUID ledgerAccountId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

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
