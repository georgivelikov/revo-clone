package com.revolutclone.analytics.readmodel;

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
 * The durable copy of one {@code EntryPostedV1} - see
 * {@link com.revolutclone.analytics.consumer.EntryPostedEventHandler}. A journal entry never
 * changes once posted, so this is the one read model in this service that is never mutated
 * after its first write: {@code apply} is only ever called once per row in practice, but takes
 * the same "overwrite every field from the event" shape as every other projection here so a
 * redelivery is trivially a no-op rather than a special case.
 *
 * <p>{@code accountId} is a <em>ledger</em> account id, not a user id - {@code EntryPostedV1}
 * carries no more than that, and CLAUDE.md rule 7 forbids resolving it by reading
 * account-service's or ledger-service's database directly. Whichever later task needs a user
 * for this row (06.3's monthly_spend, most likely) resolves that mapping itself; task 06.1
 * makes no attempt to guess how.
 */
@Entity
@Table(name = "ledger_entry_fact")
public class LedgerEntryFact implements Persistable<UUID> {

    @Id
    @Column(name = "entry_id")
    private UUID entryId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private String direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "balance_after_minor", nullable = false)
    private long balanceAfterMinor;

    @Column(nullable = false)
    private String type;

    @Column(name = "reference_id")
    private String referenceId;

    /** {@code occurredAt} of the event this row was built from - event time, never wall clock. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Transient
    private boolean isNew = true;

    protected LedgerEntryFact() {
        // JPA
    }

    public LedgerEntryFact(UUID entryId) {
        this.entryId = entryId;
    }

    public void apply(UUID accountId, String direction, long amountMinor, String currency, long balanceAfterMinor,
            String type, String referenceId, Instant occurredAt) {
        this.accountId = accountId;
        this.direction = direction;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.balanceAfterMinor = balanceAfterMinor;
        this.type = type;
        this.referenceId = referenceId;
        this.occurredAt = occurredAt;
    }

    @Override
    @Transient
    public UUID getId() {
        return entryId;
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

    public UUID getEntryId() {
        return entryId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getDirection() {
        return direction;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalanceAfterMinor() {
        return balanceAfterMinor;
    }

    public String getType() {
        return type;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
