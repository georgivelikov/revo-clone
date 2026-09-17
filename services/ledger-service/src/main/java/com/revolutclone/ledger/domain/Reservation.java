package com.revolutclone.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Mirrors plans/phase-02-ledger.md's {@code reservation} table exactly. A reservation moves
 * money from {@code available} to {@code reserved} without a posting - it is not an
 * accounting event, it is a promise. See the phase's ### Reservations table for the full
 * hold/capture/release/expiry state machine that {@code ReservationService} (task 02.6)
 * implements against this entity.
 *
 * <p>Implements {@link Persistable} with {@code isNew() == true} always, same reasoning and
 * same discipline as {@link AccountBalance}: this row is created once (state {@code HELD})
 * and mutated at most once more (a state transition to {@code CAPTURED}, {@code RELEASED} or
 * {@code EXPIRED}, with {@code resolvedAt} set). Call {@code save()} only at creation; every
 * state transition must load a managed instance and mutate it in place.
 */
@Entity
@Table(name = "reservation")
public class Reservation implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "ledger_account_id", nullable = false)
    private UUID ledgerAccountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationState state;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected Reservation() {
        // JPA
    }

    public Reservation(UUID id, UUID ledgerAccountId, long amountMinor, String currency, UUID referenceId,
            String idempotencyKey, Instant expiresAt) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be > 0, got " + amountMinor);
        }
        this.id = id;
        this.ledgerAccountId = ledgerAccountId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.state = ReservationState.HELD;
        this.referenceId = referenceId;
        this.idempotencyKey = idempotencyKey;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public void markCaptured(Instant when) {
        this.state = ReservationState.CAPTURED;
        this.resolvedAt = when;
    }

    public void markReleased(Instant when) {
        this.state = ReservationState.RELEASED;
        this.resolvedAt = when;
    }

    public void markExpired(Instant when) {
        this.state = ReservationState.EXPIRED;
        this.resolvedAt = when;
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

    public UUID getLedgerAccountId() {
        return ledgerAccountId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public ReservationState getState() {
        return state;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
