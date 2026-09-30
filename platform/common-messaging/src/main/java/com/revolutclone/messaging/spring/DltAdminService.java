package com.revolutclone.messaging.spring;

import com.revolutclone.messaging.api.EventPublisher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Component;

/**
 * plans/phase-07-resilience-and-scale.md §DLT tooling: browse a {@code <topic>.DLT} topic and
 * replay one or all of its records back to the main topic. {@code mainTopic} is always the
 * caller-facing name (e.g. {@code payment.event.v1}) - every method appends
 * {@link RetryTopicNames#DLT_SUFFIX} itself, so a caller never has to know the on-wire suffix.
 *
 * <p>Deliberately reads with a plain, manually-assigned {@link Consumer} rather than a
 * {@code @KafkaListener} or the service's own consumer group: browsing must never join a real
 * consumer group or commit an offset against one - doing so could shift a real group's
 * partition assignment or (if it happened to reuse a real group id) corrupt its offsets. Every
 * consumer this class creates gets its own throwaway group id and
 * {@code enable.auto.commit=false}.
 *
 * <p>Replaying goes through {@link EventPublisher}, the same port
 * {@code RetryTopicRelay} uses to republish from a retry topic - never a raw
 * {@code KafkaTemplate}, per CLAUDE.md's outbox-only rule. A dead-lettered record's key is
 * untouched by {@code DeadLetterPublishingRecoverer}, so replaying preserves the original
 * partition key.
 */
@Component
public class DltAdminService {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);

    /** Stop paging once this many consecutive polls come back empty - the topic is drained. */
    private static final int MAX_CONSECUTIVE_EMPTY_POLLS = 3;

    private final ConsumerFactory<Object, Object> consumerFactory;
    private final EventPublisher eventPublisher;

    public DltAdminService(ConsumerFactory<Object, Object> consumerFactory, EventPublisher eventPublisher) {
        this.consumerFactory = consumerFactory;
        this.eventPublisher = eventPublisher;
    }

    /** {@code GET /admin/dlt/{topic}}: the oldest {@code limit} dead letters, oldest first. */
    public List<DeadLetterRecord> browse(String mainTopic, int limit) {
        String dltTopic = dltTopicOf(mainTopic);
        try (Consumer<Object, Object> consumer = createConsumer()) {
            List<TopicPartition> partitions = assignAll(consumer, dltTopic);
            if (partitions.isEmpty()) {
                return List.of();
            }
            consumer.seekToBeginning(partitions);
            List<DeadLetterRecord> records = new ArrayList<>();
            int emptyPolls = 0;
            while (records.size() < limit && emptyPolls < MAX_CONSECUTIVE_EMPTY_POLLS) {
                ConsumerRecords<Object, Object> polled = consumer.poll(POLL_TIMEOUT);
                if (polled.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                for (ConsumerRecord<Object, Object> record : polled) {
                    records.add(DltHeaderReader.toDeadLetterRecord(record));
                    if (records.size() >= limit) {
                        break;
                    }
                }
            }
            return records;
        }
    }

    /** {@code POST /admin/dlt/{topic}/replay/{offset}}: republish exactly one dead letter. */
    public ReplayResult replayOne(String mainTopic, int partition, long offset) {
        TopicPartition topicPartition = new TopicPartition(dltTopicOf(mainTopic), partition);
        try (Consumer<Object, Object> consumer = createConsumer()) {
            consumer.assign(List.of(topicPartition));
            consumer.seek(topicPartition, offset);
            ConsumerRecords<Object, Object> polled = consumer.poll(POLL_TIMEOUT);
            for (ConsumerRecord<Object, Object> record : polled.records(topicPartition)) {
                if (record.offset() == offset) {
                    publish(mainTopic, record);
                    return new ReplayResult(mainTopic, partition, offset, true);
                }
            }
            return new ReplayResult(mainTopic, partition, offset, false);
        }
    }

    /** {@code POST /admin/dlt/{topic}/replay-all}: drain every dead letter currently on the topic. */
    public ReplayAllSummary replayAll(String mainTopic, boolean dryRun) {
        String dltTopic = dltTopicOf(mainTopic);
        try (Consumer<Object, Object> consumer = createConsumer()) {
            List<TopicPartition> partitions = assignAll(consumer, dltTopic);
            if (partitions.isEmpty()) {
                return new ReplayAllSummary(mainTopic, dryRun, 0);
            }
            consumer.seekToBeginning(partitions);
            int replayed = 0;
            int emptyPolls = 0;
            while (emptyPolls < MAX_CONSECUTIVE_EMPTY_POLLS) {
                ConsumerRecords<Object, Object> polled = consumer.poll(POLL_TIMEOUT);
                if (polled.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                for (ConsumerRecord<Object, Object> record : polled) {
                    if (!dryRun) {
                        publish(mainTopic, record);
                    }
                    replayed++;
                }
            }
            return new ReplayAllSummary(mainTopic, dryRun, replayed);
        }
    }

    /** The current record count on {@code <mainTopic>.DLT} - what {@code DltDepthMetrics} gauges. */
    public long depth(String mainTopic) {
        try (Consumer<Object, Object> consumer = createConsumer()) {
            List<TopicPartition> partitions = assignAll(consumer, dltTopicOf(mainTopic));
            if (partitions.isEmpty()) {
                return 0L;
            }
            Map<TopicPartition, Long> beginning = consumer.beginningOffsets(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            long total = 0L;
            for (TopicPartition partition : partitions) {
                total += end.getOrDefault(partition, 0L) - beginning.getOrDefault(partition, 0L);
            }
            return total;
        }
    }

    private void publish(String mainTopic, ConsumerRecord<Object, Object> record) {
        String key = record.key() == null ? null : record.key().toString();
        String value = record.value() == null ? null : record.value().toString();
        eventPublisher.publish(mainTopic, key, value);
    }

    private static String dltTopicOf(String mainTopic) {
        return mainTopic + RetryTopicNames.DLT_SUFFIX;
    }

    private static List<TopicPartition> assignAll(Consumer<Object, Object> consumer, String topic) {
        List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic);
        if (partitionInfos == null || partitionInfos.isEmpty()) {
            return List.of();
        }
        List<TopicPartition> partitions =
                partitionInfos.stream().map(info -> new TopicPartition(info.topic(), info.partition())).toList();
        consumer.assign(partitions);
        return partitions;
    }

    private Consumer<Object, Object> createConsumer() {
        Properties overrides = new Properties();
        overrides.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return consumerFactory.createConsumer("dlt-admin-" + UUID.randomUUID(), "dlt-admin", null, overrides);
    }
}
