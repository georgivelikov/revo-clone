package com.revolutclone.messaging.spring;

import java.time.Instant;

/**
 * One record read back off a {@code <topic>.DLT} topic, decoded by {@link DltHeaderReader} from
 * the headers {@code DeadLetterPublishingRecoverer} stamps on it - see
 * plans/phase-07-resilience-and-scale.md §DLT tooling: "browse dead letters with headers
 * (original topic, partition, offset, exception, stack trace, attempt count)". There is no
 * separate attempt-count header in this project's chain: {@link KafkaErrorHandlingConfig} uses a
 * single-attempt {@code FixedBackOff}, so which retry-topic suffix a record last failed on (not
 * captured here - that information is gone once a record lands on the DLT, since the DLT header
 * set only names the *original* topic/partition/offset from the very first failure) stands in
 * for it; every hop through the chain overwrites the exception headers with the latest failure.
 *
 * @param topic     the {@code .DLT} topic this record was read from
 * @param partition the partition it currently sits on (retry/DLT topics have 3 partitions, not
 *                   12 - a record's partition here is unrelated to its original one)
 * @param offset    its offset on that partition - together with {@code partition}, what
 *                   {@code /replay/{offset}?partition=} needs to replay this exact record
 * @param key       the entity id this record is keyed by - unchanged from the original topic
 * @param payload   the serialized {@code EventEnvelope} JSON, unchanged from the original topic
 */
public record DeadLetterRecord(
        String topic,
        int partition,
        long offset,
        String key,
        String payload,
        String originalTopic,
        Integer originalPartition,
        Long originalOffset,
        Instant originalTimestamp,
        String exceptionFqcn,
        String exceptionMessage,
        String exceptionStackTrace,
        String originalConsumerGroup) {
}
