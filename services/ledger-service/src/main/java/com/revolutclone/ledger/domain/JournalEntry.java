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
 * Mirrors plans/phase-02-ledger.md's {@code journal_entry} table exactly.
 *
 * <p>{@code idempotencyKey} is globally unique and MUST be constructed server-side - never
 * passed through from a client's {@code Idempotency-Key} header. Callers derive it from the
 * thing that caused the entry ({@code payment:<paymentId>}, {@code fxquote:<quoteId>},
 * {@code cardauth:<networkRef>}); {@code PostingEngine} (task 02.4) is responsible for
 * rejecting anything that does not match a known prefix. Forwarding a client's raw header
 * here would be a real bug: {@code payment-service} scopes idempotency keys per user, so two
 * different users choosing the same key would collide in this globally-unique column, and the
 * loser would silently receive the other user's journal entry instead of an error.
 *
 * <p>{@code metadata} is stored as {@code jsonb} via Hibernate 7.4's
 * {@code @JdbcTypeCode(SqlTypes.JSON)}, mapped as a pre-serialized JSON string - the same
 * decision {@code common-messaging}'s {@code OutboxEvent.payload} already made, kept
 * consistent project-wide per plans/phase-00-foundations.md's risk note on this mapping.
 *
 * <p>Implements {@link Persistable} for the same reason as {@link LedgerAccount} - see its
 * javadoc.
 */
@Entity
@Table(name = "journal_entry")
public class JournalEntry implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JournalEntryType type;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "reverses_entry_id")
    private UUID reversesEntryId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected JournalEntry() {
        // JPA
    }

    public JournalEntry(UUID id, String idempotencyKey, JournalEntryType type, UUID referenceId,
            UUID reversesEntryId, String metadata) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.type = type;
        this.referenceId = referenceId;
        this.reversesEntryId = reversesEntryId;
        this.metadata = metadata == null ? "{}" : metadata;
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

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public JournalEntryType getType() {
        return type;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public UUID getReversesEntryId() {
        return reversesEntryId;
    }

    public String getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
