package com.revolutclone.messaging.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /** Backs the {@code outbox.pending.count} gauge (plans/phase-08-load-and-observability.md task 3). */
    long countByPublishedAtIsNull();

    /**
     * Backs {@code outbox.oldest.age.seconds} - "more actionable than count" per the phase's own
     * Instrumentation section: a growing count might just be throughput, but an old row means the
     * poller is genuinely stuck. Empty when there is nothing pending, which {@link OutboxMetrics}
     * reports as age zero rather than a missing/stale value.
     */
    @Query("SELECT MIN(e.createdAt) FROM OutboxEvent e WHERE e.publishedAt IS NULL")
    Optional<Instant> oldestUnpublishedCreatedAt();

    /**
     * {@code SKIP LOCKED} is what makes N replicas of a service safe: a concurrent poller never
     * blocks on rows another poller already claimed, it just moves on to the next unlocked one.
     * One of the four places native Postgres SQL is sanctioned outside the ledger (see
     * plans/phase-00-foundations.md §common-messaging and phase-02 §Portability boundary) -
     * kept here rather than letting it spread.
     */
    @Query(
            value = """
                    SELECT * FROM outbox_event
                    WHERE published_at IS NULL
                    ORDER BY id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchSize
                    """,
            nativeQuery = true)
    List<OutboxEvent> lockNextBatch(int batchSize);
}
