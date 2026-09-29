package com.revolutclone.analytics.categorisation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * plans/phase-06-vaults-analytics.md's {@code transaction_category} table (created by task
 * 06.1's up-front scaffold migration, populated for the first time by this task). One row per
 * card authorization, written once - by {@link TransactionCategorisationService} the first time
 * {@link com.revolutclone.analytics.consumer.AuthorizationApprovedEventHandler} learns an
 * authorization's merchant details - and never updated by this task. Task 06.4's retro-apply is
 * the only thing allowed to change a row here once written.
 *
 * <p>{@link Persistable}, same pattern as {@code CardAuthorizationFact}: the id is assigned in
 * the constructor rather than generated, so without this interface Spring Data would issue a
 * {@code merge} (a select-then-insert/update) instead of a plain insert for a row
 * {@link TransactionCategorisationService} already confirmed does not exist yet.
 */
@Entity
@Table(name = "transaction_category")
public class TransactionCategory implements Persistable<UUID> {

    @Id
    @Column(name = "authorization_id")
    private UUID authorizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String source;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Transient
    private boolean isNew = true;

    protected TransactionCategory() {
        // JPA
    }

    public TransactionCategory(UUID authorizationId, UUID userId, String category, String source,
            Instant assignedAt) {
        this.authorizationId = authorizationId;
        this.userId = userId;
        this.category = category;
        this.source = source;
        this.assignedAt = assignedAt;
    }

    @Override
    @Transient
    public UUID getId() {
        return authorizationId;
    }

    @Override
    @Transient
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public UUID getAuthorizationId() {
        return authorizationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getCategory() {
        return category;
    }

    public String getSource() {
        return source;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }
}
