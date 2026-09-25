package com.revolutclone.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Mirrors plans/phase-02-ledger.md's {@code account_balance} table exactly. A cache, updated
 * in the same transaction as the postings that move it - see plans/phase-02-ledger.md's
 * ### Balance column semantics: which column a posting moves depends on whether it is
 * capturing a hold, and getting that distinction wrong is invisible to the zero-sum
 * invariant. {@code version} backs JPA optimistic locking (task 02.5's retry wrapper).
 *
 * <p>Implements {@link Persistable} with {@code isNew() == true} always, same reasoning as
 * {@link LedgerAccount} - a manually-assigned id routes {@code save()} through {@code merge()}
 * instead of {@code persist()} unless overridden. Unlike {@code LedgerAccount}/
 * {@code JournalEntry}/{@code Posting}, this entity IS mutated after creation (every posting
 * or reservation touches {@code available}/{@code reserved}), so hardcoding {@code isNew()}
 * true is only safe under the same discipline already established by identity-service's
 * {@code UserProfile}: <b>call {@code save()} exactly once, at creation.</b> Every later
 * mutation must load a managed instance ({@code repository.findById}, or a
 * {@code @Transactional} method that already holds one) and mutate its fields directly,
 * relying on Hibernate's dirty checking to flush the UPDATE - never construct a fresh
 * instance with a pre-set id and call {@code save()} again, or the write to
 * {@code available_minor}/{@code reserved_minor} that a concurrent posting made in between will
 * be silently clobbered by {@code persist()} colliding with the primary key, or by a stale
 * detached instance overwriting fresher state.
 */
@Entity
@Table(name = "account_balance")
public class AccountBalance implements Persistable<UUID> {

    @Id
    @Column(name = "ledger_account_id")
    private UUID ledgerAccountId;

    @Column(name = "available_minor", nullable = false)
    private long availableMinor;

    @Column(name = "reserved_minor", nullable = false)
    private long reservedMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Version
    @Column(nullable = false)
    private long version;

    protected AccountBalance() {
        // JPA
    }

    /** A freshly opened account always starts at zero on both columns. */
    public AccountBalance(UUID ledgerAccountId, String currency) {
        this.ledgerAccountId = ledgerAccountId;
        this.availableMinor = 0L;
        this.reservedMinor = 0L;
        this.currency = currency;
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

    public long getAvailableMinor() {
        return availableMinor;
    }

    public long getReservedMinor() {
        return reservedMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public long getVersion() {
        return version;
    }

    /** DEBIT, no reservation: available -= amount. */
    public void debitAvailable(long amountMinor) {
        this.availableMinor -= amountMinor;
    }

    /** CREDIT, no reservation: available += amount. */
    public void creditAvailable(long amountMinor) {
        this.availableMinor += amountMinor;
    }

    /** DEBIT capturing a hold: reserved -= amount only - available was already moved at hold time. */
    public void releaseReservedOnCapture(long amountMinor) {
        this.reservedMinor -= amountMinor;
    }

    /** hold: available -= amount, reserved += amount. */
    public void moveToReserved(long amountMinor) {
        this.availableMinor -= amountMinor;
        this.reservedMinor += amountMinor;
    }

    /** release (full or the unused remainder of a partial capture): reserved -= amount, available += amount. */
    public void moveFromReservedToAvailable(long amountMinor) {
        this.reservedMinor -= amountMinor;
        this.availableMinor += amountMinor;
    }

    /**
     * Directly mutates {@code available_minor} with no corresponding posting - not a real money
     * movement, and unlike every method above it, deliberately desynchronizes this cached total
     * from {@code sum(postings)}. Exists ONLY for task 02.10's admin corrupt-balance endpoint, so
     * the reconciliation job's safety net can be watched actually catching a drifted account.
     * Never call this from business logic.
     */
    public void corruptAvailableForTesting(long deltaMinor) {
        this.availableMinor += deltaMinor;
    }
}
