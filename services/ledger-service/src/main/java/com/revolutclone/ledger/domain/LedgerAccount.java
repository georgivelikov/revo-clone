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
 * Mirrors plans/phase-02-ledger.md's {@code ledger_account} table exactly. {@code ownerRef}
 * is a free-form reference string ({@code "user:<uuid>"}, {@code "system:fx-pool"}, ...), not
 * a foreign key - the ledger does not know about {@code account-service}'s {@code account}
 * table or any other owning entity, per the phase's own boundary rule that this service knows
 * nothing about payments, cards or FX beyond balanced entries and reservations.
 *
 * <p>Implements {@link Persistable} and always reports {@code isNew() == true}: a
 * manually-assigned (non-generated) id makes Spring Data's default heuristic route the first
 * {@code save()} through JPA {@code merge()} instead of {@code persist()}, silently dropping
 * {@code @PrePersist}-populated fields from the instance the caller is still holding - the
 * exact bug found live in identity-service's {@code UserProfile} (task 01.2) and
 * account-service's {@code Account} (task 01.5). Every entity in this package with a
 * manually-assigned id repeats this fix rather than rediscovering the bug a third time.
 */
@Entity
@Table(name = "ledger_account")
public class LedgerAccount implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "owner_ref", nullable = false)
    private String ownerRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LedgerAccountType type;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    /**
     * The {@code account-service} account this ledger account holds the money for, or
     * {@code null} for a system account that has no counterpart there. The only reason the
     * ledger stores an identifier belonging to another service: {@code PaymentInitiatedV1} names
     * its two sides by account-service account id, and {@code owner_ref} ({@code user:<userId>})
     * cannot tell one of a user's wallets from another. See
     * {@code V6__ledger_account_external_ref.sql}.
     */
    @Column(name = "external_account_id")
    private UUID externalAccountId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerAccount() {
        // JPA
    }

    public LedgerAccount(UUID id, String ownerRef, LedgerAccountType type, String currency) {
        this(id, ownerRef, type, currency, null);
    }

    public LedgerAccount(UUID id, String ownerRef, LedgerAccountType type, String currency,
            UUID externalAccountId) {
        this.id = id;
        this.ownerRef = ownerRef;
        this.type = type;
        this.currency = currency;
        this.externalAccountId = externalAccountId;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
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

    public String getOwnerRef() {
        return ownerRef;
    }

    public LedgerAccountType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public UUID getExternalAccountId() {
        return externalAccountId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
