package com.revolutclone.messaging.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.revolutclone.messaging.testsupport.RecordingEventPublisher;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;

/**
 * plans/phase-07-resilience-and-scale.md §DLT tooling, exercised against a mocked
 * {@link Consumer} rather than a real broker - same environment limitation recorded in
 * docs/spring-boot-4-notes.md (Testcontainers' Kafka module does not start on this machine).
 * {@link DltAdminService} only ever calls plain {@link Consumer}/{@link ConsumerFactory} methods,
 * so the browse/replay/depth algorithms are fully verifiable this way; only the real broker
 * round-trip (this DLT topic naming actually matching what {@code KafkaErrorHandlingConfig}
 * produces) needs the manual verification the retry-topic-chain tasks already relied on.
 */
class DltAdminServiceTest {

    private static final String MAIN_TOPIC = "payment.event.v1";
    private static final String DLT_TOPIC = MAIN_TOPIC + ".DLT";

    @SuppressWarnings("unchecked")
    private final ConsumerFactory<Object, Object> consumerFactory = mock(ConsumerFactory.class);

    @SuppressWarnings("unchecked")
    private final Consumer<Object, Object> consumer = mock(Consumer.class);

    private final RecordingEventPublisher eventPublisher = new RecordingEventPublisher();

    private final DltAdminService dltAdminService = new DltAdminService(consumerFactory, eventPublisher);

    @BeforeEach
    void wireConsumer() {
        when(consumerFactory.createConsumer(anyString(), anyString(), isNull(), any(Properties.class)))
                .thenReturn(consumer);
    }

    private static List<TopicPartition> threePartitions() {
        return List.of(new TopicPartition(DLT_TOPIC, 0), new TopicPartition(DLT_TOPIC, 1),
                new TopicPartition(DLT_TOPIC, 2));
    }

    private static List<PartitionInfo> partitionInfos() {
        Node node = new Node(0, "localhost", 9092);
        return threePartitions().stream()
                .map(tp -> new PartitionInfo(tp.topic(), tp.partition(), node, new Node[0], new Node[0]))
                .toList();
    }

    private static ConsumerRecords<Object, Object> batchOf(TopicPartition partition,
            ConsumerRecord<Object, Object>... records) {
        return new ConsumerRecords<>(Map.of(partition, List.of(records)), Map.of());
    }

    @Test
    void browseReturnsDecodedRecordsOldestFirst() {
        when(consumer.partitionsFor(DLT_TOPIC)).thenReturn(partitionInfos());
        TopicPartition partitionZero = new TopicPartition(DLT_TOPIC, 0);
        ConsumerRecord<Object, Object> first = new ConsumerRecord<>(DLT_TOPIC, 0, 10L, "account-1", "{\"event\":1}");
        ConsumerRecord<Object, Object> second = new ConsumerRecord<>(DLT_TOPIC, 0, 11L, "account-2", "{\"event\":2}");
        when(consumer.poll(any(Duration.class)))
                .thenReturn(batchOf(partitionZero, first, second), ConsumerRecords.empty());

        List<DeadLetterRecord> records = dltAdminService.browse(MAIN_TOPIC, 100);

        assertThat(records).hasSize(2);
        assertThat(records.get(0).key()).isEqualTo("account-1");
        assertThat(records.get(0).offset()).isEqualTo(10L);
        assertThat(records.get(1).key()).isEqualTo("account-2");
        verify(consumer).assign(threePartitions());
        verify(consumer).seekToBeginning(threePartitions());
    }

    @Test
    void browseStopsAtTheRequestedLimitEvenWithMoreRecordsInTheBatch() {
        when(consumer.partitionsFor(DLT_TOPIC)).thenReturn(partitionInfos());
        TopicPartition partitionZero = new TopicPartition(DLT_TOPIC, 0);
        ConsumerRecord<Object, Object> first = new ConsumerRecord<>(DLT_TOPIC, 0, 10L, "k1", "v1");
        ConsumerRecord<Object, Object> second = new ConsumerRecord<>(DLT_TOPIC, 0, 11L, "k2", "v2");
        when(consumer.poll(any(Duration.class))).thenReturn(batchOf(partitionZero, first, second));

        List<DeadLetterRecord> records = dltAdminService.browse(MAIN_TOPIC, 1);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).offset()).isEqualTo(10L);
    }

    @Test
    void browseReturnsEmptyWhenTheDltTopicDoesNotExist() {
        when(consumer.partitionsFor(DLT_TOPIC)).thenReturn(List.of());

        assertThat(dltAdminService.browse(MAIN_TOPIC, 100)).isEmpty();
    }

    @Test
    void replayOneRepublishesTheMatchingRecordToTheMainTopicWithItsOriginalKey() {
        TopicPartition partition = new TopicPartition(DLT_TOPIC, 1);
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>(DLT_TOPIC, 1, 42L, "account-9", "{\"event\":9}");
        when(consumer.poll(any(Duration.class))).thenReturn(batchOf(partition, record));

        ReplayResult result = dltAdminService.replayOne(MAIN_TOPIC, 1, 42L);

        assertThat(result.replayed()).isTrue();
        assertThat(result.topic()).isEqualTo(MAIN_TOPIC);
        assertThat(result.partition()).isEqualTo(1);
        assertThat(result.offset()).isEqualTo(42L);
        assertThat(eventPublisher.publishedPartitionKeys()).containsExactly("account-9");
        verify(consumer).seek(partition, 42L);
    }

    @Test
    void replayOneReportsNotReplayedWhenTheOffsetIsNotFound() {
        when(consumer.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());

        ReplayResult result = dltAdminService.replayOne(MAIN_TOPIC, 0, 7L);

        assertThat(result.replayed()).isFalse();
        assertThat(eventPublisher.publishedPartitionKeys()).isEmpty();
    }

    @Test
    void replayAllInDryRunModeCountsWithoutPublishing() {
        when(consumer.partitionsFor(DLT_TOPIC)).thenReturn(partitionInfos());
        TopicPartition partitionZero = new TopicPartition(DLT_TOPIC, 0);
        ConsumerRecord<Object, Object> first = new ConsumerRecord<>(DLT_TOPIC, 0, 1L, "k1", "v1");
        ConsumerRecord<Object, Object> second = new ConsumerRecord<>(DLT_TOPIC, 0, 2L, "k2", "v2");
        when(consumer.poll(any(Duration.class)))
                .thenReturn(batchOf(partitionZero, first, second), ConsumerRecords.empty());

        ReplayAllSummary summary = dltAdminService.replayAll(MAIN_TOPIC, true);

        assertThat(summary.replayedCount()).isEqualTo(2);
        assertThat(summary.dryRun()).isTrue();
        assertThat(eventPublisher.publishedPartitionKeys()).isEmpty();
    }

    @Test
    void replayAllRepublishesEveryRecordWhenNotADryRun() {
        when(consumer.partitionsFor(DLT_TOPIC)).thenReturn(partitionInfos());
        TopicPartition partitionZero = new TopicPartition(DLT_TOPIC, 0);
        ConsumerRecord<Object, Object> first = new ConsumerRecord<>(DLT_TOPIC, 0, 1L, "k1", "v1");
        ConsumerRecord<Object, Object> second = new ConsumerRecord<>(DLT_TOPIC, 0, 2L, "k2", "v2");
        when(consumer.poll(any(Duration.class)))
                .thenReturn(batchOf(partitionZero, first, second), ConsumerRecords.empty());

        ReplayAllSummary summary = dltAdminService.replayAll(MAIN_TOPIC, false);

        assertThat(summary.replayedCount()).isEqualTo(2);
        assertThat(summary.dryRun()).isFalse();
        assertThat(eventPublisher.publishedPartitionKeys()).containsExactly("k1", "k2");
    }

    @Test
    void depthSumsEndMinusBeginningOffsetsAcrossAllPartitions() {
        when(consumer.partitionsFor(DLT_TOPIC)).thenReturn(partitionInfos());
        List<TopicPartition> partitions = threePartitions();
        when(consumer.beginningOffsets(partitions)).thenReturn(
                Map.of(partitions.get(0), 0L, partitions.get(1), 5L, partitions.get(2), 0L));
        when(consumer.endOffsets(partitions)).thenReturn(
                Map.of(partitions.get(0), 3L, partitions.get(1), 5L, partitions.get(2), 7L));

        assertThat(dltAdminService.depth(MAIN_TOPIC)).isEqualTo(10L);
    }

    @Test
    void depthIsZeroWhenTheDltTopicDoesNotExist() {
        when(consumer.partitionsFor(DLT_TOPIC)).thenReturn(null);

        assertThat(dltAdminService.depth(MAIN_TOPIC)).isEqualTo(0L);
    }
}
