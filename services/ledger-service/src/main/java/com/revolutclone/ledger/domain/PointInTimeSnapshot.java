package com.revolutclone.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * One row per {@code ledger_account_id}: the latest {@code EntryPostedV1} on-or-before the
 * {@code asOf} timestamp a {@code com.revolutclone.ledger.replay.PointInTimeService} query was
 * run for. {@code V8__point_in_time_snapshot.sql}'s own comment explains why this is a real,
 * Flyway-owned table rather than ad-hoc DDL - "throwaway" describes its contents (truncated and
 * repopulated by every query), not its schema. Lives in this package, not alongside
 * {@code PointInTimeService} itself, because {@code LedgerServiceApplication}'s
 * {@code @EntityScan}/{@code @EnableJpaRepositories} are both scoped to this package - see that
 * class's own javadoc on why the default component-scan backs off once either annotation is
 * present anywhere in the context.
 *
 * <p>Implements {@link Persistable} with {@code isNew() == true} always, same reasoning as
 * {@link LedgerAccount}: a manually-assigned id would otherwise route {@code save()} through
 * {@code merge()} (an extra, pointless SELECT) instead of {@code persist()} - and here every row
 * really is a fresh insert, since the service deletes the whole table before repopulating it.
 */
@Entity
@Table(name = "point_in_time_snapshot")
public class PointInTimeSnapshot implements Persistable<UUID> {

    @Id
    @Column(name = "ledger_account_id")
    private UUID ledgerAccountId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "balance_after_minor", nullable = false)
    private long balanceAfterMinor;

    @Column(name = "entry_id", nullable = false)
    private UUID entryId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected PointInTimeSnapshot() {
        // JPA
    }

    public PointInTimeSnapshot(UUID ledgerAccountId, String currency, long balanceAfterMinor, UUID entryId,
            Instant occurredAt) {
        this.ledgerAccountId = ledgerAccountId;
        this.currency = currency;
        this.balanceAfterMinor = balanceAfterMinor;
        this.entryId = entryId;
        this.occurredAt = occurredAt;
    }

    @Override
    @Transient
    public boolean isNew() {
        return true;
    }

    @Override
    @Transient
    public UUID getId() {
        return ledgerAccountId;
    }

    public UUID getLedgerAccountId() {
        return ledgerAccountId;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalanceAfterMinor() {
        return balanceAfterMinor;
    }

    public UUID getEntryId() {
        return entryId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
