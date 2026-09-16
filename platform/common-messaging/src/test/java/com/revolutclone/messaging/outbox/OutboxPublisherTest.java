package com.revolutclone.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.messaging.testsupport.RecordingEventPublisher;
import com.revolutclone.messaging.testsupport.TestMessagingApplication;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves {@code SKIP LOCKED} is what it claims to be: two concurrent publishers must never
 * emit the same outbox row twice. The first poller is made to block mid-transaction (still
 * holding its row locks) so the second poller's concurrent poll runs while every seeded row is
 * locked, and must come back empty - not partially, not with a duplicate, empty.
 */
@Testcontainers
@SpringBootTest(classes = TestMessagingApplication.class)
class OutboxPublisherTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private RecordingEventPublisher recordingEventPublisher;

    @Test
    @Timeout(30)
    void concurrentPublishersNeverClaimTheSameRowTwice() throws Exception {
        int seeded = 5;
        List<Long> seededIds = seedUnpublishedRows(seeded);

        CountDownLatch firstPollerEnteredFirstPublish = new CountDownLatch(1);
        CountDownLatch releaseFirstPoller = new CountDownLatch(1);
        recordingEventPublisher.blockNextCallUntilReleased(firstPollerEnteredFirstPublish, releaseFirstPoller);

        CompletableFuture<Integer> firstPoller =
                CompletableFuture.supplyAsync(() -> outboxPublisher.pollAndPublish(100), Executors.newSingleThreadExecutor());

        // Wait until the first poller is inside its transaction, mid-batch, still holding every
        // row lock (FOR UPDATE locks every row the SELECT returned, not just the one blocked on).
        assertThat(firstPollerEnteredFirstPublish.await(10, TimeUnit.SECONDS)).isTrue();

        int secondPollerBatch = outboxPublisher.pollAndPublish(100);
        assertThat(secondPollerBatch)
                .as("a concurrent poller must see nothing while every row is locked by the first poller")
                .isZero();

        releaseFirstPoller.countDown();
        int firstPollerBatch = firstPoller.get(10, TimeUnit.SECONDS);
        assertThat(firstPollerBatch).isEqualTo(seeded);

        assertThat(recordingEventPublisher.publishedPartitionKeys()).hasSize(seeded);
        assertThat(recordingEventPublisher.publishedPartitionKeys())
                .as("no row published twice")
                .doesNotHaveDuplicates();

        for (Long id : seededIds) {
            assertThat(repository.findById(id).orElseThrow().getPublishedAt()).isNotNull();
        }
    }

    private List<Long> seedUnpublishedRows(int count) {
        Instant now = Instant.now();
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> repository.save(new OutboxEvent(
                        "account.account.v1", "acct-" + i, "TestEvent",
                        "{\"noop\":true}", "acct-" + i, "account.account.v1", now)))
                .map(OutboxEvent::getId)
                .toList();
    }
}
