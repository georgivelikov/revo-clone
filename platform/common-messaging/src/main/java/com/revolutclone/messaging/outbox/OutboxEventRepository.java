package com.revolutclone.messaging.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

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
