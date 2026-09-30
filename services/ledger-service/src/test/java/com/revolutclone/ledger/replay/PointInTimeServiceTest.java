package com.revolutclone.ledger.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.revolutclone.ledger.domain.PointInTimeSnapshot;
import com.revolutclone.ledger.domain.PointInTimeSnapshotRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.ConsumerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * plans/phase-07-resilience-and-scale.md's ### Replay, flavour 2, exercised against a mocked
 * {@link Consumer} rather than a real broker - same environment limitation
 * {@code DltAdminServiceTest} already records (Testcontainers' Kafka module does not start on
 * this machine). {@link PointInTimeService} only ever calls plain
 * {@link Consumer}/{@link ConsumerFactory} methods, so the "latest qualifying entry per account"
 * algorithm is fully verifiable this way.
 */
class PointInTimeServiceTest {

    private static final String TOPIC = "ledger.entry.v1";
    private static final UUID ACCOUNT_A = UUID.randomUUID();
    private static final UUID ACCOUNT_B = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private final ConsumerFactory<Object, Object> consumerFactory = mock(ConsumerFactory.class);

    @SuppressWarnings("unchecked")
    private final Consumer<Object, Object> consumer = mock(Consumer.class);

    private final PointInTimeSnapshotRepository snapshots = mock(PointInTimeSnapshotRepository.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    private final PointInTimeService pointInTimeService = new PointInTimeService(consumerFactory, jsonMapper, snapshots);

    @BeforeEach
    void wireConsumer() {
        when(consumerFactory.createConsumer(anyString(), anyString(), isNull(), any(Properties.class)))
                .thenReturn(consumer);
    }

    private static TopicPartition partition() {
        return new TopicPartition(TOPIC, 0);
    }

    private static List<PartitionInfo> partitionInfos() {
        Node node = new Node(0, "localhost", 9092);
        return List.of(new PartitionInfo(TOPIC, 0, node, new Node[0], new Node[0]));
    }

    private static String entryPostedJson(UUID entryId, UUID accountId, long balanceAfterMinor, Instant occurredAt) {
        return """
                {"eventId":"%s","occurredAt":"%s","correlationId":"corr-1","type":"EntryPostedV1","version":1,
                 "payload":{"entryId":"%s","accountId":"%s","direction":"CREDIT","amountMinor":1000,
                            "currency":"EUR","balanceAfterMinor":%d,"type":"TRANSFER","referenceId":null}}
                """.formatted(UUID.randomUUID(), occurredAt, entryId, accountId, balanceAfterMinor);
    }

    private static String entryRejectedJson(Instant occurredAt) {
        return """
                {"eventId":"%s","occurredAt":"%s","correlationId":"corr-2","type":"EntryRejectedV1","version":1,
                 "payload":{}}
                """.formatted(UUID.randomUUID(), occurredAt);
    }

    @SuppressWarnings("unchecked")
    private void stubTwoPolls(ConsumerRecords<Object, Object> firstBatch) {
        when(consumer.partitionsFor(TOPIC)).thenReturn(partitionInfos());
        when(consumer.endOffsets(List.of(partition()))).thenReturn(Map.of(partition(), (long) firstBatch.count()));
        when(consumer.position(partition())).thenReturn(0L, (long) firstBatch.count());
        when(consumer.poll(any(Duration.class))).thenReturn(firstBatch, ConsumerRecords.empty());
    }

    @Test
    void keepsTheLatestQualifyingEntryPerAccount() {
        Instant asOf = Instant.parse("2026-01-01T12:00:00Z");
        Instant t1 = Instant.parse("2026-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-01-01T11:00:00Z");
        UUID firstEntry = UUID.randomUUID();
        UUID secondEntry = UUID.randomUUID();

        ConsumerRecord<Object, Object> older = new ConsumerRecord<>(TOPIC, 0, 0L, ACCOUNT_A.toString(),
                entryPostedJson(firstEntry, ACCOUNT_A, 1000L, t1));
        ConsumerRecord<Object, Object> newer = new ConsumerRecord<>(TOPIC, 0, 1L, ACCOUNT_A.toString(),
                entryPostedJson(secondEntry, ACCOUNT_A, 2500L, t2));
        stubTwoPolls(new ConsumerRecords<>(Map.of(partition(), List.of(older, newer)), Map.of()));

        List<PointInTimeSnapshot> result = pointInTimeService.query(asOf);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getLedgerAccountId()).isEqualTo(ACCOUNT_A);
        assertThat(result.get(0).getBalanceAfterMinor()).isEqualTo(2500L);
        assertThat(result.get(0).getEntryId()).isEqualTo(secondEntry);
        verify(consumer).seekToBeginning(List.of(partition()));
    }

    @Test
    void ignoresEntriesAfterTheAsOfTimestamp() {
        Instant asOf = Instant.parse("2026-01-01T10:30:00Z");
        Instant before = Instant.parse("2026-01-01T10:00:00Z");
        Instant after = Instant.parse("2026-01-01T11:00:00Z");
        UUID beforeEntry = UUID.randomUUID();

        ConsumerRecord<Object, Object> withinWindow = new ConsumerRecord<>(TOPIC, 0, 0L, ACCOUNT_A.toString(),
                entryPostedJson(beforeEntry, ACCOUNT_A, 500L, before));
        ConsumerRecord<Object, Object> outsideWindow = new ConsumerRecord<>(TOPIC, 0, 1L, ACCOUNT_A.toString(),
                entryPostedJson(UUID.randomUUID(), ACCOUNT_A, 9999L, after));
        stubTwoPolls(new ConsumerRecords<>(Map.of(partition(), List.of(withinWindow, outsideWindow)), Map.of()));

        List<PointInTimeSnapshot> result = pointInTimeService.query(asOf);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getBalanceAfterMinor()).isEqualTo(500L);
        assertThat(result.get(0).getEntryId()).isEqualTo(beforeEntry);
    }

    @Test
    void ignoresNonEntryPostedEventTypesOnTheSameTopic() {
        Instant asOf = Instant.parse("2026-01-01T12:00:00Z");
        Instant t1 = Instant.parse("2026-01-01T10:00:00Z");

        ConsumerRecord<Object, Object> rejected =
                new ConsumerRecord<>(TOPIC, 0, 0L, ACCOUNT_B.toString(), entryRejectedJson(t1));
        stubTwoPolls(new ConsumerRecords<>(Map.of(partition(), List.of(rejected)), Map.of()));

        List<PointInTimeSnapshot> result = pointInTimeService.query(asOf);

        assertThat(result).isEmpty();
    }

    @Test
    void tracksMultipleAccountsIndependently() {
        Instant asOf = Instant.parse("2026-01-01T12:00:00Z");
        Instant t1 = Instant.parse("2026-01-01T10:00:00Z");
        UUID entryA = UUID.randomUUID();
        UUID entryB = UUID.randomUUID();

        ConsumerRecord<Object, Object> forA = new ConsumerRecord<>(TOPIC, 0, 0L, ACCOUNT_A.toString(),
                entryPostedJson(entryA, ACCOUNT_A, 100L, t1));
        ConsumerRecord<Object, Object> forB = new ConsumerRecord<>(TOPIC, 0, 1L, ACCOUNT_B.toString(),
                entryPostedJson(entryB, ACCOUNT_B, 200L, t1));
        stubTwoPolls(new ConsumerRecords<>(Map.of(partition(), List.of(forA, forB)), Map.of()));

        List<PointInTimeSnapshot> result = pointInTimeService.query(asOf);

        assertThat(result).extracting(PointInTimeSnapshot::getLedgerAccountId).containsExactlyInAnyOrder(ACCOUNT_A,
                ACCOUNT_B);
    }

    @Test
    void writesTheReplayResultIntoTheSnapshotTable() {
        Instant asOf = Instant.parse("2026-01-01T12:00:00Z");
        Instant t1 = Instant.parse("2026-01-01T10:00:00Z");
        UUID entryA = UUID.randomUUID();

        ConsumerRecord<Object, Object> forA = new ConsumerRecord<>(TOPIC, 0, 0L, ACCOUNT_A.toString(),
                entryPostedJson(entryA, ACCOUNT_A, 100L, t1));
        stubTwoPolls(new ConsumerRecords<>(Map.of(partition(), List.of(forA)), Map.of()));

        pointInTimeService.query(asOf);

        verify(snapshots).deleteAllInBatch();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PointInTimeSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(snapshots).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getLedgerAccountId()).isEqualTo(ACCOUNT_A);
    }

    @Test
    void emptyTopicProducesAnEmptyResult() {
        when(consumer.partitionsFor(TOPIC)).thenReturn(List.of());

        List<PointInTimeSnapshot> result = pointInTimeService.query(Instant.now());

        assertThat(result).isEmpty();
        verify(snapshots).deleteAllInBatch();
    }
}
