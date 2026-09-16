package com.revolutclone.messaging.testsupport;

import com.revolutclone.messaging.api.EventPublisher;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Test double standing in for the real {@code KafkaEventPublisher} so
 * {@code OutboxPublisherTest} can prove {@code SKIP LOCKED} behavior against a real Postgres
 * Testcontainer without needing a live Kafka broker. {@code @Primary} so it wins over the real
 * bean when both are in the test context.
 */
@Component
@Primary
public class RecordingEventPublisher implements EventPublisher {

    private final List<String> publishedPartitionKeys = new CopyOnWriteArrayList<>();
    private final AtomicBoolean blockOnFirstCall = new AtomicBoolean(false);
    private volatile CountDownLatch enteredFirstPublish;
    private volatile CountDownLatch releaseGate;

    @Override
    public void publish(String topic, String partitionKey, String payloadJson) {
        publishedPartitionKeys.add(partitionKey);
        if (blockOnFirstCall.compareAndSet(true, false)) {
            enteredFirstPublish.countDown();
            await(releaseGate);
        }
    }

    /** Blocks the very next call to {@link #publish} until {@code releaseGate} counts down to 0. */
    public void blockNextCallUntilReleased(CountDownLatch enteredFirstPublish, CountDownLatch releaseGate) {
        this.enteredFirstPublish = enteredFirstPublish;
        this.releaseGate = releaseGate;
        this.blockOnFirstCall.set(true);
    }

    public List<String> publishedPartitionKeys() {
        return Collections.unmodifiableList(publishedPartitionKeys);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while blocked in test publisher", e);
        }
    }
}
