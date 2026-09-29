package com.revolutclone.analytics.vault;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * plans/phase-06-vaults-analytics.md's {@code vault} table (created by task 06.1's scaffold
 * migration, populated for the first time by this task, 06.6). {@code accountId} is
 * account-service's id for the vault's backing {@code VAULT}-type account, not ledger-service's -
 * the money itself lives in ledger-service, reached only through payment-service's ordinary
 * transfer path ({@link VaultTransferService}), never directly. See the phase's "Vaults" section
 * for why the three-way split (this row / the account / the money) is deliberate rather than an
 * oversight.
 *
 * <p>Unlike account-service's {@code Account} or common-messaging's {@code ProcessedEvent}, this
 * entity does <strong>not</strong> force {@code isNew() == true} via {@link
 * org.springframework.data.domain.Persistable}: a vault's {@code goal_minor}/{@code
 * roundup_enabled}/{@code roundup_source_card_id} are mutated after creation (task 06.6's update
 * endpoint), so it must be possible to {@code save()} an already-persisted instance as an
 * {@code UPDATE}. {@code createdAt} is set in the constructor instead of a {@code @PrePersist}
 * callback for exactly this reason - it is fixed at construction time regardless of whether the
 * eventual {@code save()} takes the {@code persist()} or {@code merge()} path.
 *
 * <p><strong>No round-up multiplier column, deliberately, for now.</strong> The phase's prose
 * mentions "multiplier options (1x, 2x, 5x)" for round-ups, but its own {@code vault} table SQL
 * (reproduced verbatim by task 06.1's scaffold migration) has no such column, and none of task
 * 06.7's three pinned tests exercise one - {@code RoundUpTest}'s own worked example is a plain 1x
 * round-up. Rather than add a column no test drives, this is left as an implicit 1x and the gap is
 * documented here for whichever of 06.8 (query endpoints) or 06.9 (Vaults screen) needs it: adding
 * a {@code roundup_multiplier smallint not null default 1} column, an update-endpoint parameter,
 * and multiplying {@link com.revolutclone.analytics.roundup.RoundUp}'s result by it in
 * {@link com.revolutclone.analytics.roundup.RoundUpService} is the whole of that future change.
 */
@Entity
@Table(name = "vault")
public class Vault {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "goal_minor")
    private Long goalMinor;

    @Column(name = "roundup_enabled", nullable = false)
    private boolean roundupEnabled;

    @Column(name = "roundup_source_card_id")
    private UUID roundupSourceCardId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Vault() {
        // JPA
    }

    public Vault(UUID id, UUID userId, UUID accountId, String name, String currency, Long goalMinor,
            boolean roundupEnabled, UUID roundupSourceCardId) {
        this.id = id;
        this.userId = userId;
        this.accountId = accountId;
        this.name = name;
        this.currency = currency;
        this.goalMinor = goalMinor;
        this.roundupEnabled = roundupEnabled;
        this.roundupSourceCardId = roundupSourceCardId;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getName() {
        return name;
    }

    public String getCurrency() {
        return currency;
    }

    public Long getGoalMinor() {
        return goalMinor;
    }

    public boolean isRoundupEnabled() {
        return roundupEnabled;
    }

    public UUID getRoundupSourceCardId() {
        return roundupSourceCardId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Task 06.6's update endpoint: goal and round-up settings only - name/currency/account never change. */
    public void updateSettings(Long goalMinor, boolean roundupEnabled, UUID roundupSourceCardId) {
        this.goalMinor = goalMinor;
        this.roundupEnabled = roundupEnabled;
        this.roundupSourceCardId = roundupSourceCardId;
    }
}
