package com.revolutclone.analytics.budget;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * plans/phase-06-vaults-analytics.md's {@code budget} table (created up front by task 06.1's
 * {@code V3__analytics_schema.sql} scaffold; populated for the first time by this task, 06.5).
 * One row per {@code (user_id, category, month, currency)} - the table's own unique constraint -
 * carrying a spending ceiling for that bucket plus the two idempotency-guard timestamps that stop
 * a replay from re-notifying a user for six months of history: {@code alerted_at_80}/{@code
 * alerted_at_100} record the {@code occurredAt} of the settlement event that first crossed each
 * threshold, never {@link java.time.Instant#now()} - see {@link BudgetThresholdService} for why.
 *
 * <p>Unlike {@link com.revolutclone.analytics.spend.MonthlySpend}, this row is <strong>not</strong>
 * a pure projection of the event stream - it is user-configured data (the limit the user chose),
 * so it is not expected to be dropped and rebuilt when {@code monthly_spend} is. That is exactly
 * what makes the {@code alerted_at_*} columns a durable guard rather than one that evaporates on
 * every rebuild: see {@code ReplayDoesNotRealertTest}.
 */
@Entity
@Table(name = "budget")
public class Budget {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private LocalDate month;

    @Column(name = "limit_minor", nullable = false)
    private long limitMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "alerted_at_80")
    private Instant alertedAt80;

    @Column(name = "alerted_at_100")
    private Instant alertedAt100;

    protected Budget() {
        // JPA
    }

    public Budget(UUID id, UUID userId, String category, LocalDate month, long limitMinor, String currency) {
        this.id = id;
        this.userId = userId;
        this.category = category;
        this.month = month;
        this.limitMinor = limitMinor;
        this.currency = currency;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getCategory() {
        return category;
    }

    public LocalDate getMonth() {
        return month;
    }

    public long getLimitMinor() {
        return limitMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getAlertedAt80() {
        return alertedAt80;
    }

    public Instant getAlertedAt100() {
        return alertedAt100;
    }

    /** Setting the same budget again (the endpoint's upsert) corrects the limit, nothing else. */
    public void setLimitMinor(long limitMinor) {
        this.limitMinor = limitMinor;
    }

    /**
     * Stamped with the triggering settlement's own {@code occurredAt} - never wall-clock - so the
     * guard is deterministic under replay: a replayed event with an old {@code occurredAt} sets the
     * same value it set the first time, not "whenever the replay happened to run."
     */
    public void markAlertedAt80(Instant occurredAt) {
        this.alertedAt80 = occurredAt;
    }

    public void markAlertedAt100(Instant occurredAt) {
        this.alertedAt100 = occurredAt;
    }
}
