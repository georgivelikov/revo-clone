package com.revolutclone.fx.rate;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.FxRateTickV1;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.listener.ConsumerSeekAware.ConsumerSeekCallback;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * plans/phase-04-fx-multicurrency.md's fx-service design detail: "Reads for quoting come from
 * memory; a restart replays the tail of the topic to warm up," and its Tests table names this
 * exact behaviour: "Restarted fx-service warms its cache from the topic tail."
 *
 * <p>Drives {@link FxRateTickHandler} directly rather than through a real broker, for the reason
 * recorded in docs/spring-boot-4-notes.md: Testcontainers' Kafka module does not start on this
 * machine (Docker Desktop on WSL2 has no real {@code --network=host}). That costs nothing here -
 * the handler is the warm-up projection, and {@link FxRateTickKafkaListener} contributes only the
 * topic binding and the replay seek, asserted separately below against the real bean - the same
 * split payment-service's {@code AccountReadModelProjectionTest} uses for the equivalent
 * compacted-topic case.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000",
        // Task 04.4 gave the context a ledger client: its base URL is bound at startup, so it has
        // to resolve even in a test that never calls the ledger. Nothing here places a hold.
        "revolut.fx.ledger.base-url=http://ledger.invalid"
})
class RateReplayOnStartupTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void oidcIssuer(DynamicPropertyRegistry registry) {
        // No Keycloak needed: nothing here goes through the web layer, the resource-server bean
        // only has to be constructible at context startup.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://127.0.0.1:8180/realms/revolut");
    }

    @Autowired
    private FxRateTickHandler handler;

    @Autowired
    private FxRateCache cache;

    @Autowired
    private FxRateTickKafkaListener listener;

    @Test
    void aFreshCacheAnswersNothingUntilTicksHaveBeenReplayed() {
        assertThat(cache.mid("EUR", "USD")).isEmpty();
    }

    @Test
    void replayingTheTopicsRetentionWindowWarmsTheCacheWithTheLatestMidPerPair() {
        // Simulates seekToBeginning() replaying whatever ticks still exist inside
        // fx.rate.tick.v1's 1-hour retention (infra/kafka/create-topics.sh) - several ticks per
        // pair, in publication order, exactly what a freshly (re)started instance receives.
        handler.handle(tick("EUR/GBP", "0.8600000000", Instant.parse("2026-03-01T10:00:00Z")));
        handler.handle(tick("EUR/GBP", "0.8605000000", Instant.parse("2026-03-01T10:00:00.500Z")));
        handler.handle(tick("EUR/GBP", "0.8611000000", Instant.parse("2026-03-01T10:00:01Z")));
        handler.handle(tick("EUR/USD", "1.0800000000", Instant.parse("2026-03-01T10:00:00Z")));
        handler.handle(tick("GBP/USD", "1.2600000000", Instant.parse("2026-03-01T10:00:00Z")));

        assertThat(cache.mid("EUR", "GBP"))
                .as("the cache converges on the last tick replayed, not the first")
                .contains("0.8611000000");
        assertThat(cache.mid("EUR", "USD")).contains("1.0800000000");
        assertThat(cache.mid("GBP", "USD")).contains("1.2600000000");
    }

    @Test
    void theListenerSeeksToTheBeginningOnEveryAssignmentSoARestartReplaysTheRetentionWindow() {
        RecordingSeekCallback callback = new RecordingSeekCallback();
        TopicPartition zero = new TopicPartition("fx.rate.tick.v1", 0);
        TopicPartition seven = new TopicPartition("fx.rate.tick.v1", 7);

        listener.onPartitionsAssigned(Map.of(zero, 4200L, seven, 900L), callback);

        assertThat(callback.seekedToBeginning)
                .as("every assigned partition must be replayed, not resumed from a committed offset")
                .hasSize(1);
        assertThat(callback.seekedToBeginning.getFirst()).containsExactlyInAnyOrder(zero, seven);
    }

    private static EventEnvelope<FxRateTickV1> tick(String pair, String mid, Instant asOf) {
        return new EventEnvelope<>(
                UUID.randomUUID(), asOf, "corr-" + pair, "FxRateTickV1", 1,
                new FxRateTickV1(pair, mid, asOf, "simulator"));
    }

    /**
     * Records the one call the listener is contracted to make. Hand-written rather than mocked
     * so the unsupported methods fail loudly if the listener ever starts seeking some other way.
     */
    private static final class RecordingSeekCallback implements ConsumerSeekCallback {

        private final List<Collection<TopicPartition>> seekedToBeginning = new ArrayList<>();

        @Override
        public void seekToBeginning(Collection<TopicPartition> partitions) {
            seekedToBeginning.add(List.copyOf(partitions));
        }

        @Override
        public void seekToBeginning(String topic, int partition) {
            throw new UnsupportedOperationException("unexpected per-partition seekToBeginning");
        }

        @Override
        public void seek(String topic, int partition, long offset) {
            throw new UnsupportedOperationException("unexpected seek");
        }

        @Override
        public void seek(String topic, int partition, Function<Long, Long> offsetComputeFunction) {
            throw new UnsupportedOperationException("unexpected computed seek");
        }

        @Override
        public void seekToEnd(String topic, int partition) {
            throw new UnsupportedOperationException("unexpected seekToEnd");
        }

        @Override
        public void seekRelative(String topic, int partition, long offset, boolean toCurrent) {
            throw new UnsupportedOperationException("unexpected seekRelative");
        }

        @Override
        public void seekToTimestamp(String topic, int partition, long timestamp) {
            throw new UnsupportedOperationException("unexpected seekToTimestamp");
        }

        @Override
        public void seekToTimestamp(Collection<TopicPartition> topicPartitions, long timestamp) {
            throw new UnsupportedOperationException("unexpected seekToTimestamp");
        }
    }
}
