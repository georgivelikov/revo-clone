package com.revolutclone.messaging.outbox;

import com.revolutclone.messaging.api.EventPublisher;
import java.time.Clock;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only thing in this module that runs unattended: polls for unpublished outbox rows and
 * hands each one to {@link EventPublisher}. {@code SKIP LOCKED} (in
 * {@link OutboxEventRepository#lockNextBatch}) is what lets N replicas of the same service run
 * this poller concurrently without ever double-publishing a row - each replica's transaction
 * simply skips whatever another replica already has locked.
 */
@Component
public class OutboxPublisher {

    private static final int BATCH_SIZE = 100;

    private final OutboxEventRepository repository;
    private final EventPublisher eventPublisher;
    private final Clock clock;

    public OutboxPublisher(OutboxEventRepository repository, EventPublisher eventPublisher, Clock clock) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${revolut.messaging.outbox.poll-delay-ms:1000}")
    @Transactional
    public void publishPending() {
        pollAndPublish(BATCH_SIZE);
    }

    /**
     * Exposed with an explicit batch size so tests can drive one poll cycle deterministically
     * instead of waiting on the scheduler.
     */
    @Transactional
    public int pollAndPublish(int batchSize) {
        List<OutboxEvent> batch = repository.lockNextBatch(batchSize);
        for (OutboxEvent row : batch) {
            eventPublisher.publish(row.getTopic(), row.getPartitionKey(), row.getPayload());
            row.incrementAttempts();
            row.markPublished(clock.instant());
        }
        return batch.size();
    }
}
