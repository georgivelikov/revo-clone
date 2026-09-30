package com.revolutclone.simulator.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/**
 * {@link ChaosDuplicator#lastN} in isolation from the raw {@code KafkaConsumer} it feeds - see
 * that method's javadoc for why it trusts input order rather than re-sorting.
 */
class ChaosDuplicatorTest {

    @Test
    void keepsOnlyTheLastCountRecordsInOrder() {
        List<ConsumerRecord<String, String>> records = List.of(
                record("k1", 10L, "first"),
                record("k2", 11L, "second"),
                record("k3", 12L, "third"));

        List<ConsumerRecord<String, String>> kept = ChaosDuplicator.lastN(records, 2);

        assertThat(kept).extracting(ConsumerRecord::value).containsExactly("second", "third");
    }

    @Test
    void returnsEverythingWhenFewerRecordsThanCount() {
        List<ConsumerRecord<String, String>> records = List.of(record("k1", 10L, "only"));

        List<ConsumerRecord<String, String>> kept = ChaosDuplicator.lastN(records, 100);

        assertThat(kept).extracting(ConsumerRecord::value).containsExactly("only");
    }

    @Test
    void preservesTheOriginalKeyForEachKeptRecord() {
        List<ConsumerRecord<String, String>> records = List.of(
                record("account-1", 10L, "v1"),
                record("account-2", 11L, "v2"));

        List<ConsumerRecord<String, String>> kept = ChaosDuplicator.lastN(records, 2);

        assertThat(kept).extracting(ConsumerRecord::key).containsExactly("account-1", "account-2");
    }

    private static ConsumerRecord<String, String> record(String key, long offset, String value) {
        return new ConsumerRecord<>("some-topic", 0, offset, key, value);
    }
}
