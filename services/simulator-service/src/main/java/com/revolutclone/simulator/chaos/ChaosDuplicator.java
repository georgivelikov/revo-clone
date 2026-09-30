package com.revolutclone.simulator.chaos;

import com.revolutclone.messaging.api.EventPublisher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * plans/phase-07-resilience-and-scale.md's {@code duplicate <topic>} lever: "Re-emit the last N
 * messages - observe idempotency holding." A raw {@link KafkaConsumer}, not a
 * {@code @KafkaListener}: this needs to read a bounded, historical slice of a topic on demand from
 * an HTTP call, not run continuously as part of a consumer group - a fresh, randomly-named
 * {@code group.id} per call (below) means this never steals partitions from, or commits an offset
 * that would affect, any real consumer group.
 *
 * <p>Republishing goes back through {@link EventPublisher} with each record's original key
 * preserved, so a duplicate lands on the same partition the original did - the whole point of the
 * experiment is proving {@code processed_event} absorbs a genuine redelivery, not a reshuffled one.
 */
@Component
public class ChaosDuplicator {

    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);
    private static final Duration OVERALL_TIMEOUT = Duration.ofSeconds(10);

    private final String bootstrapServers;
    private final EventPublisher eventPublisher;

    public ChaosDuplicator(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            EventPublisher eventPublisher) {
        this.bootstrapServers = bootstrapServers;
        this.eventPublisher = eventPublisher;
    }

    /** @return how many of the last {@code count} messages on {@code topic} were re-published. */
    public int duplicateLast(String topic, int count) {
        List<ConsumerRecord<String, String>> tail = readTail(topic, count);
        List<ConsumerRecord<String, String>> toReplay = lastN(tail, count);
        for (ConsumerRecord<String, String> record : toReplay) {
            eventPublisher.publish(topic, record.key(), record.value());
        }
        return toReplay.size();
    }

    /**
     * The last {@code count} records, trusting the caller's ordering rather than re-sorting by
     * record timestamp: Kafka guarantees offset order within a partition (which is exactly the
     * order a sequence of {@code poll()} calls on one partition returns), and there is no
     * meaningful global ordering across partitions to sort into instead - two records on
     * different partitions have no "happened before" relationship this lever needs to honour.
     * Package-visible so it is unit-testable without a broker.
     */
    static List<ConsumerRecord<String, String>> lastN(List<ConsumerRecord<String, String>> records, int count) {
        if (records.size() <= count) {
            return new ArrayList<>(records);
        }
        return new ArrayList<>(records.subList(records.size() - count, records.size()));
    }

    private List<ConsumerRecord<String, String>> readTail(String topic, int count) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "chaos-duplicator-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic);
            List<TopicPartition> partitions = partitionInfos.stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            consumer.assign(partitions);

            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            for (TopicPartition partition : partitions) {
                long end = endOffsets.getOrDefault(partition, 0L);
                consumer.seek(partition, Math.max(0, end - count));
            }

            List<ConsumerRecord<String, String>> collected = new ArrayList<>();
            long deadline = System.currentTimeMillis() + OVERALL_TIMEOUT.toMillis();
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(POLL_TIMEOUT);
                polled.forEach(collected::add);
                boolean caughtUpEverywhere = partitions.stream()
                        .allMatch(partition -> consumer.position(partition) >= endOffsets.getOrDefault(partition, 0L));
                if (caughtUpEverywhere) {
                    break;
                }
            }
            return collected;
        }
    }
}
