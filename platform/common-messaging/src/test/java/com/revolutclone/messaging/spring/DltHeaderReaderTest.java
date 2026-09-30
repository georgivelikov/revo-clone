package com.revolutclone.messaging.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * The header decoding half of plans/phase-07-resilience-and-scale.md §DLT tooling - see
 * docs/spring-boot-4-notes.md's entry on {@code DeadLetterPublishingRecoverer}'s headers for the
 * exact constants and byte encoding this mirrors.
 */
class DltHeaderReaderTest {

    @Test
    void decodesEveryDeadLetterHeaderDeadLetterPublishingRecovererStamps() {
        ConsumerRecord<Object, Object> record =
                new ConsumerRecord<>("payment.event.v1.DLT", 2, 55L, "payment-123", "{\"event\":\"payload\"}");
        record.headers()
                .add(KafkaHeaders.DLT_ORIGINAL_TOPIC, "payment.event.v1".getBytes(StandardCharsets.UTF_8))
                .add(KafkaHeaders.DLT_ORIGINAL_PARTITION, intBytes(4))
                .add(KafkaHeaders.DLT_ORIGINAL_OFFSET, longBytes(99L))
                .add(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP, longBytes(1_700_000_000_000L))
                .add(KafkaHeaders.DLT_EXCEPTION_FQCN,
                        "java.lang.IllegalArgumentException".getBytes(StandardCharsets.UTF_8))
                .add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "negative amount".getBytes(StandardCharsets.UTF_8))
                .add(KafkaHeaders.DLT_EXCEPTION_STACKTRACE, "stack trace here".getBytes(StandardCharsets.UTF_8))
                .add(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP, "payment-service".getBytes(StandardCharsets.UTF_8));

        DeadLetterRecord decoded = DltHeaderReader.toDeadLetterRecord(record);

        assertThat(decoded.topic()).isEqualTo("payment.event.v1.DLT");
        assertThat(decoded.partition()).isEqualTo(2);
        assertThat(decoded.offset()).isEqualTo(55L);
        assertThat(decoded.key()).isEqualTo("payment-123");
        assertThat(decoded.payload()).isEqualTo("{\"event\":\"payload\"}");
        assertThat(decoded.originalTopic()).isEqualTo("payment.event.v1");
        assertThat(decoded.originalPartition()).isEqualTo(4);
        assertThat(decoded.originalOffset()).isEqualTo(99L);
        assertThat(decoded.originalTimestamp()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        assertThat(decoded.exceptionFqcn()).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(decoded.exceptionMessage()).isEqualTo("negative amount");
        assertThat(decoded.exceptionStackTrace()).isEqualTo("stack trace here");
        assertThat(decoded.originalConsumerGroup()).isEqualTo("payment-service");
    }

    @Test
    void missingHeadersDecodeToNullRatherThanThrowing() {
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("card.authorization.v1.DLT", 0, 1L, null, null);

        DeadLetterRecord decoded = DltHeaderReader.toDeadLetterRecord(record);

        assertThat(decoded.key()).isNull();
        assertThat(decoded.payload()).isNull();
        assertThat(decoded.originalTopic()).isNull();
        assertThat(decoded.originalPartition()).isNull();
        assertThat(decoded.originalOffset()).isNull();
        assertThat(decoded.originalTimestamp()).isNull();
        assertThat(decoded.exceptionFqcn()).isNull();
    }

    private static byte[] intBytes(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    private static byte[] longBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }
}
