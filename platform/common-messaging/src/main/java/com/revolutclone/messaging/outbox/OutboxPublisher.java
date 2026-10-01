package com.revolutclone.messaging.outbox;

import com.revolutclone.messaging.api.EventPublisher;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
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

    /**
     * Task 08.9's tuning change: docs/perf-runs/08.6-baseline.json and 08.7-ramp.json both
     * measured this poller's fixed 1-second delay as the real sustained-throughput ceiling
     * (~100 rows/sec/service regardless of how much backlog was waiting), exactly
     * plans/phase-08's predicted bottleneck #3. Adaptive polling - re-poll immediately whenever
     * the last batch came back full, since a full batch means more rows are very likely still
     * waiting - removes the artificial 1-second floor between batches while the backlog is
     * actually draining. This cap bounds how many back-to-back full batches one scheduler tick
     * will drain before yielding to the fixed delay again, so a backlog that never stops growing
     * cannot monopolize the scheduler thread forever.
     */
    private static final int MAX_BATCHES_PER_TICK = 50;

    private final OutboxEventRepository repository;
    private final EventPublisher eventPublisher;
    private final Clock clock;
    private final ChaosOutboxControl chaosOutboxControl;
    private final ObjectProvider<OutboxPublisher> self;

    public OutboxPublisher(OutboxEventRepository repository, EventPublisher eventPublisher, Clock clock,
            ChaosOutboxControl chaosOutboxControl, ObjectProvider<OutboxPublisher> self) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.chaosOutboxControl = chaosOutboxControl;
        this.self = self;
    }

    @Scheduled(fixedDelayString = "${revolut.messaging.outbox.poll-delay-ms:1000}")
    public void publishPending() {
        // Routed through the proxy (self.getObject()), not a direct this.pollAndPublish() call,
        // so each batch commits in its own transaction - row locks are released between
        // batches instead of held for the whole tick, and a crash mid-loop never loses a batch
        // that already published and committed.
        OutboxPublisher proxy = self.getObject();
        int published;
        int batches = 0;
        do {
            published = proxy.pollAndPublish(BATCH_SIZE);
            batches++;
        } while (published == BATCH_SIZE && batches < MAX_BATCHES_PER_TICK);
    }

    /**
     * Exposed with an explicit batch size so tests can drive one poll cycle deterministically
     * instead of waiting on the scheduler.
     *
     * <p>Checked first, before even locking a batch: {@code outbox.pause} (plans/phase-07's Chaos
     * control plane) must leave pending rows exactly as they are in the database, not lock and
     * immediately release them.
     */
    @Transactional
    public int pollAndPublish(int batchSize) {
        if (chaosOutboxControl.isPaused()) {
            return 0;
        }
        List<OutboxEvent> batch = repository.lockNextBatch(batchSize);
        for (OutboxEvent row : batch) {
            eventPublisher.publish(row.getTopic(), row.getPartitionKey(), row.getPayload());
            row.incrementAttempts();
            row.markPublished(clock.instant());
        }
        return batch.size();
    }
}
