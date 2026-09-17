package com.revolutclone.account.domain;

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
 * Mirrors plans/phase-01-identity-account-gateway.md's account table exactly. Never gains a
 * balance column - see the phase's own Risks &amp; gotchas: the ledger (Phase 02) is the one
 * source of truth for balances, and a placeholder column here would become a second one that
 * drifts.
 *
 * <p>Implements {@link Persistable} and always reports {@code isNew() == true}, same reasoning
 * as {@code common-messaging}'s {@code ProcessedEvent}: with a manually-assigned (non-generated)
 * {@code id}, Spring Data's default heuristic treats a populated id as "existing" and routes
 * {@code save()} through JPA {@code merge()} instead of {@code persist()}. {@code merge()}
 * operates on an internal copy, so {@code @PrePersist} never touches the instance the caller is
 * still holding - found live (task 01.5) as {@code createdAt} coming back {@code null} in the
 * HTTP response immediately after a successful create, even though the column is correctly
 * populated in the database. An account is only ever inserted once, never updated via this
 * entity, so always-new is correct and forces {@code persist()}.
 */
@Entity
@Table(name = "account")
public class Account implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    /**
     * Never null - see plans/phase-01-identity-account-gateway.md's account-service section:
     * Postgres treats NULLs as distinct in a unique constraint, so a nullable name would let
     * {@code unique(user_id, currency, type, name)} permit unlimited duplicate wallets per
     * user, silently defeating DuplicateWalletConstraintTest / UserRegisteredConsumerTest's
     * redelivery-protection proof. Wallets always use "".
     */
    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountState state;

    /** Filled in by Phase 02 once the ledger exists. Null until then. */
    @Column(name = "ledger_account_id")
    private UUID ledgerAccountId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Account() {
        // JPA
    }

    public Account(UUID id, UUID userId, String currency, AccountType type, String name, AccountState state) {
        this.id = id;
        this.userId = userId;
        this.currency = currency;
        this.type = type;
        this.name = name;
        this.state = state;
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

    public UUID getUserId() {
        return userId;
    }

    public String getCurrency() {
        return currency;
    }

    public AccountType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public AccountState getState() {
        return state;
    }

    public UUID getLedgerAccountId() {
        return ledgerAccountId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
