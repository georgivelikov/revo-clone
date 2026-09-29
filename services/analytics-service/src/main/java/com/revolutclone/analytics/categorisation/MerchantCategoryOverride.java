package com.revolutclone.analytics.categorisation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The top layer of categorisation, task 06.2's own table (the phase's Design detail names user
 * overrides but does not give this table's schema - see the task's own scope note). One row per
 * (user, merchant name): setting an override again for the same pair replaces it, per
 * {@link MerchantCategoryOverrideService#setOverride}.
 *
 * <p>This task only makes an override affect <em>future</em> categorisation - {@link
 * com.revolutclone.analytics.consumer.AuthorizationApprovedEventHandler} consults it the next
 * time that merchant is seen. Correcting past transactions and their aggregates once an override
 * is set is task 06.4's retro-apply, deliberately not attempted here.
 */
@Entity
@Table(name = "merchant_category_override")
@IdClass(MerchantCategoryOverride.Key.class)
public class MerchantCategoryOverride {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(name = "merchant_name", nullable = false)
    private String merchantName;

    @Column(nullable = false)
    private String category;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantCategoryOverride() {
        // JPA
    }

    public MerchantCategoryOverride(UUID userId, String merchantName, String category, Instant updatedAt) {
        this.userId = userId;
        this.merchantName = merchantName;
        this.category = category;
        this.updatedAt = updatedAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public String getCategory() {
        return category;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** The composite key, an {@code @IdClass} for the same reason as card-service's {@code CardLimit.Key}. */
    public static class Key implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private UUID userId;
        private String merchantName;

        public Key() {
            // JPA
        }

        public Key(UUID userId, String merchantName) {
            this.userId = userId;
            this.merchantName = merchantName;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof Key key
                    && Objects.equals(userId, key.userId)
                    && Objects.equals(merchantName, key.merchantName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, merchantName);
        }

        @Override
        public String toString() {
            return userId + "/" + merchantName;
        }
    }
}
