package com.revolutclone.analytics.spend;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * plans/phase-06-vaults-analytics.md's {@code monthly_spend} table (created up front by task
 * 06.1's {@code V3__analytics_schema.sql} scaffold; populated for the first time by this task).
 * One row per {@code (user_id, month, category, currency)} - never summed across currencies, per
 * the phase's own multi-currency warning - incrementally upserted by
 * {@link MonthlySpendRepository#increment} as each settlement arrives.
 *
 * <p>This entity is a read path only. Every write goes through the repository's native
 * {@code ON CONFLICT} upsert, never through {@code save()} - see that method's javadoc for why a
 * SQL-level upsert, not a JPA read-modify-write, is what keeps a concurrent increment from being
 * lost. There is accordingly no public constructor and no setters: nothing in Java code is
 * supposed to construct or mutate a row directly.
 */
@Entity
@Table(name = "monthly_spend")
@IdClass(MonthlySpend.Key.class)
public class MonthlySpend {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(nullable = false)
    private LocalDate month;

    @Id
    @Column(nullable = false)
    private String category;

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "total_minor", nullable = false)
    private long totalMinor;

    @Column(name = "txn_count", nullable = false)
    private int txnCount;

    protected MonthlySpend() {
        // JPA - and the native upsert, which never goes through this entity at all.
    }

    public UUID getUserId() {
        return userId;
    }

    public LocalDate getMonth() {
        return month;
    }

    public String getCategory() {
        return category;
    }

    public String getCurrency() {
        return currency;
    }

    public long getTotalMinor() {
        return totalMinor;
    }

    public int getTxnCount() {
        return txnCount;
    }

    /**
     * The composite key, as a JPA {@code @IdClass} rather than a record - the specification
     * requires a no-argument constructor and mutable fields, the same reasoning
     * {@code CardLimit.Key} in card-service already documents for its own two-part key.
     */
    public static class Key implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private UUID userId;
        private LocalDate month;
        private String category;
        private String currency;

        public Key() {
            // JPA
        }

        public Key(UUID userId, LocalDate month, String category, String currency) {
            this.userId = userId;
            this.month = month;
            this.category = category;
            this.currency = currency;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof Key key && Objects.equals(userId, key.userId)
                    && Objects.equals(month, key.month) && Objects.equals(category, key.category)
                    && Objects.equals(currency, key.currency);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, month, category, currency);
        }

        @Override
        public String toString() {
            return userId + "/" + month + "/" + category + "/" + currency;
        }
    }
}
