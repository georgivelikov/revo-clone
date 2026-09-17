package com.revolutclone.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Mirrors plans/phase-02-ledger.md's {@code posting} table exactly. One row per leg of a
 * {@link JournalEntry}; the entry is only balanced once every one of its postings is in (see
 * {@code V4__balanced_trigger.sql}).
 *
 * <p>{@code reservationId} is set only when this posting captures a hold - see the phase's
 * ### Balance column semantics table, reproduced at the top of {@code PostingEngine} (task
 * 02.4): a DEBIT posting with {@code reservationId} set decrements {@code reserved}, not
 * {@code available} - the money already left {@code available} at hold time. Getting that
 * distinction wrong is invisible to the zero-sum invariant and is the single easiest way to
 * corrupt this service.
 *
 * <p>No {@code entryId}/{@code ledgerAccountId} JPA relationship mapping - plain UUID foreign
 * key columns, matching this project's established convention (see {@code Account}'s
 * {@code ledgerAccountId} in account-service) of avoiding entity graphs where a raw id
 * reference is all that is needed.
 *
 * <p>Implements {@link Persistable} for the same reason as {@link LedgerAccount} - see its
 * javadoc.
 */
@Entity
@Table(name = "posting")
public class Posting implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "entry_id", nullable = false)
    private UUID entryId;

    @Column(name = "ledger_account_id", nullable = false)
    private UUID ledgerAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PostingDirection direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(nullable = false)
    private int seq;

    protected Posting() {
        // JPA
    }

    public Posting(UUID id, UUID entryId, UUID ledgerAccountId, PostingDirection direction, long amountMinor,
            String currency, UUID reservationId, int seq) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be > 0, got " + amountMinor);
        }
        this.id = id;
        this.entryId = entryId;
        this.ledgerAccountId = ledgerAccountId;
        this.direction = direction;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.reservationId = reservationId;
        this.seq = seq;
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

    public UUID getEntryId() {
        return entryId;
    }

    public UUID getLedgerAccountId() {
        return ledgerAccountId;
    }

    public PostingDirection getDirection() {
        return direction;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public int getSeq() {
        return seq;
    }
}
