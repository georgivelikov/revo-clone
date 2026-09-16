package com.revolutclone.messaging.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A plain JPA entity, no {@code @MappedSuperclass}. Written by {@link OutboxAppenderImpl}
 * inside the caller's business transaction; read and marked published by
 * {@link OutboxPublisher}. The {@code payload} column holds the fully-serialized
 * {@code EventEnvelope<? extends DomainEvent>} JSON, mapped as {@code jsonb} via Hibernate 7.4's
 * {@code @JdbcTypeCode(SqlTypes.JSON)} - decided once here per
 * plans/phase-00-foundations.md §Risks, and every service that includes this migration reuses
 * this exact mapping.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "type", nullable = false)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "partition_key", nullable = false)
    private String partitionKey;

    @Column(name = "topic", nullable = false)
    private String topic;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    protected OutboxEvent() {
        // JPA
    }

    public OutboxEvent(String aggregateType, String aggregateId, String type, String payload,
            String partitionKey, String topic, Instant createdAt) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.type = type;
        this.payload = payload;
        this.partitionKey = partitionKey;
        this.topic = topic;
        this.createdAt = createdAt;
        this.attempts = 0;
    }

    public Long getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public String getTopic() {
        return topic;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public void markPublished(Instant when) {
        this.publishedAt = when;
    }

    public void incrementAttempts() {
        this.attempts++;
    }
}
