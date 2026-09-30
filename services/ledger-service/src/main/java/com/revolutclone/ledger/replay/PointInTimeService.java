package com.revolutclone.ledger.replay;

import com.revolutclone.ledger.domain.PointInTimeSnapshot;
import com.revolutclone.ledger.domain.PointInTimeSnapshotRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * plans/phase-07-resilience-and-scale.md's ### Replay, flavour 2: "replay ledger.entry.v1 up to
 * a timestamp into a throwaway schema to answer 'what was every balance last Tuesday at
 * 14:00?'". Read-only - unlike {@code analytics-rebuild}'s reset of the real
 * {@code analytics-service} consumer group, this never touches it: same reasoning as
 * {@code common-messaging}'s {@code DltAdminService}, a plain, manually-assigned
 * {@link Consumer} with its own throwaway group id reads the topic from the beginning without
 * joining or committing against any real consumer group.
 *
 * <p>{@code EntryPostedV1.balanceAfterMinor} is already the resulting account balance at the
 * moment it was posted (see the event's own javadoc), so "every balance as of {@code asOf}" is
 * simply the latest {@code EntryPostedV1} per account with {@code occurredAt <= asOf} - no
 * summation of posting amounts required. {@code ledger.entry.v1} is keyed by
 * {@code ledgerAccountId} (plans/phase-02-ledger.md), so every event for one account lands on
 * the same partition in posting order; replaying a partition in offset order and unconditionally
 * overwriting the map entry for each account therefore always leaves that account's latest
 * qualifying snapshot, with no timestamp comparison needed.
 */
@Component
public class PointInTimeService {

    private static final String TOPIC = "ledger.entry.v1";
    private static final String ENTRY_POSTED_TYPE = "EntryPostedV1";
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);

    /** Safety net alongside the end-offset check below, same cap {@code DltAdminService} uses. */
    private static final int MAX_CONSECUTIVE_EMPTY_POLLS = 3;

    private final ConsumerFactory<Object, Object> consumerFactory;
    private final JsonMapper jsonMapper;
    private final PointInTimeSnapshotRepository snapshots;

    public PointInTimeService(ConsumerFactory<Object, Object> consumerFactory, JsonMapper jsonMapper,
            PointInTimeSnapshotRepository snapshots) {
        this.consumerFactory = consumerFactory;
        this.jsonMapper = jsonMapper;
        this.snapshots = snapshots;
    }

    /**
     * Replays {@code ledger.entry.v1} from offset zero and writes the result into
     * {@code point_in_time_snapshot}, truncating whatever a previous call left there - see that
     * table's own migration comment for why a real, Flyway-owned table stands in for a literal
     * "throwaway schema".
     */
    @Transactional
    public List<PointInTimeSnapshot> query(Instant asOf) {
        List<PointInTimeSnapshot> rows = new ArrayList<>(replay(asOf).values());
        rows.sort(Comparator.comparing(PointInTimeSnapshot::getLedgerAccountId));
        snapshots.deleteAllInBatch();
        snapshots.saveAll(rows);
        return rows;
    }

    private Map<UUID, PointInTimeSnapshot> replay(Instant asOf) {
        Map<UUID, PointInTimeSnapshot> latestByAccount = new LinkedHashMap<>();
        try (Consumer<Object, Object> consumer = createConsumer()) {
            List<TopicPartition> partitions = assignAll(consumer, TOPIC);
            if (partitions.isEmpty()) {
                return latestByAccount;
            }
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            consumer.seekToBeginning(partitions);

            int emptyPolls = 0;
            while (!isDrained(consumer, partitions, endOffsets) && emptyPolls < MAX_CONSECUTIVE_EMPTY_POLLS) {
                ConsumerRecords<Object, Object> polled = consumer.poll(POLL_TIMEOUT);
                if (polled.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                for (ConsumerRecord<Object, Object> record : polled) {
                    applyIfQualifyingEntryPosted(record, asOf, latestByAccount);
                }
            }
        }
        return latestByAccount;
    }

    private void applyIfQualifyingEntryPosted(ConsumerRecord<Object, Object> record, Instant asOf,
            Map<UUID, PointInTimeSnapshot> latestByAccount) {
        if (record.value() == null) {
            return;
        }
        JsonNode node = jsonMapper.readTree(record.value().toString());
        JsonNode type = node.get("type");
        if (type == null || !ENTRY_POSTED_TYPE.equals(type.asString())) {
            return;
        }
        Instant occurredAt = Instant.parse(node.get("occurredAt").asString());
        if (occurredAt.isAfter(asOf)) {
            return;
        }
        JsonNode payload = node.get("payload");
        UUID accountId = UUID.fromString(payload.get("accountId").asString());
        String currency = payload.get("currency").asString();
        long balanceAfterMinor = payload.get("balanceAfterMinor").asLong();
        UUID entryId = UUID.fromString(payload.get("entryId").asString());

        latestByAccount.put(accountId,
                new PointInTimeSnapshot(accountId, currency, balanceAfterMinor, entryId, occurredAt));
    }

    private static boolean isDrained(Consumer<Object, Object> consumer, List<TopicPartition> partitions,
            Map<TopicPartition, Long> endOffsets) {
        for (TopicPartition partition : partitions) {
            if (consumer.position(partition) < endOffsets.getOrDefault(partition, 0L)) {
                return false;
            }
        }
        return true;
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
        return consumerFactory.createConsumer("point-in-time-" + UUID.randomUUID(), "point-in-time", null, overrides);
    }
}
