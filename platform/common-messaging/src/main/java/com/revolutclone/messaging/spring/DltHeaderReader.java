package com.revolutclone.messaging.spring;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * Decodes the headers {@link org.springframework.kafka.listener.DeadLetterPublishingRecoverer}
 * stamps on every record it republishes to a {@code .DLT} topic. See
 * docs/spring-boot-4-notes.md's entry on this recoverer for the exact constants and byte
 * encoding (UTF-8 strings for the exception fields, big-endian int/long for
 * partition/offset/timestamp) - confirmed against the Spring Kafka 4.1.1 reference docs, since
 * the library jar could not be unzipped directly in this environment.
 */
final class DltHeaderReader {

    private DltHeaderReader() {
    }

    static DeadLetterRecord toDeadLetterRecord(ConsumerRecord<Object, Object> record) {
        Headers headers = record.headers();
        return new DeadLetterRecord(
                record.topic(),
                record.partition(),
                record.offset(),
                record.key() == null ? null : record.key().toString(),
                record.value() == null ? null : record.value().toString(),
                asString(headers, KafkaHeaders.DLT_ORIGINAL_TOPIC),
                asInt(headers, KafkaHeaders.DLT_ORIGINAL_PARTITION),
                asLong(headers, KafkaHeaders.DLT_ORIGINAL_OFFSET),
                asInstant(headers, KafkaHeaders.DLT_ORIGINAL_TIMESTAMP),
                asString(headers, KafkaHeaders.DLT_EXCEPTION_FQCN),
                asString(headers, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                asString(headers, KafkaHeaders.DLT_EXCEPTION_STACKTRACE),
                asString(headers, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP));
    }

    private static String asString(Headers headers, String key) {
        Header header = headers.lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static Integer asInt(Headers headers, String key) {
        Header header = headers.lastHeader(key);
        return header == null ? null : ByteBuffer.wrap(header.value()).getInt();
    }

    private static Long asLong(Headers headers, String key) {
        Header header = headers.lastHeader(key);
        return header == null ? null : ByteBuffer.wrap(header.value()).getLong();
    }

    private static Instant asInstant(Headers headers, String key) {
        Long epochMillis = asLong(headers, key);
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis);
    }
}
