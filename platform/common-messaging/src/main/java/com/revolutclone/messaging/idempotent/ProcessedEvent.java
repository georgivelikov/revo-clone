package com.revolutclone.messaging.idempotent;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * One row per (consumer group, event) that has already been handled. The primary key is the
 * dedup key itself, so a second delivery of the same {@code eventId} to the same
 * {@code consumer_group} fails on insert with a unique-constraint violation rather than
 * needing a separate existence check - see {@link IdempotentHandler}.
 *
 * <p>Implements {@link Persistable} and always reports {@code isNew() == true}: with a plain
 * {@code @EmbeddedId} and a manually-assigned (non-generated) key, Spring Data's default
 * new-vs-existing heuristic treats a populated id as "existing" and routes {@code save()}
 * through JPA {@code merge()} - which upserts silently on a duplicate key instead of throwing.
 * This entity is only ever inserted once, never updated, so always-new is correct and forces
 * {@code save()} through {@code persist()}, which does throw on the constraint violation that
 * {@link IdempotentHandler} depends on.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent implements Persistable<ProcessedEvent.Key> {

    @EmbeddedId
    private Key key;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
        // JPA
    }

    public ProcessedEvent(String consumerGroup, UUID eventId, Instant processedAt) {
        this.key = new Key(consumerGroup, eventId);
        this.processedAt = processedAt;
    }

    public Key getKey() {
        return key;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    @Override
    @Transient
    public Key getId() {
        return key;
    }

    @Override
    @Transient
    public boolean isNew() {
        return true;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "consumer_group", nullable = false)
        private String consumerGroup;

        @Column(name = "event_id", nullable = false)
        private UUID eventId;

        protected Key() {
            // JPA
        }

        public Key(String consumerGroup, UUID eventId) {
            this.consumerGroup = consumerGroup;
            this.eventId = eventId;
        }

        public String getConsumerGroup() {
            return consumerGroup;
        }

        public UUID getEventId() {
            return eventId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other)) {
                return false;
            }
            return Objects.equals(consumerGroup, other.consumerGroup) && Objects.equals(eventId, other.eventId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(consumerGroup, eventId);
        }
    }
}
