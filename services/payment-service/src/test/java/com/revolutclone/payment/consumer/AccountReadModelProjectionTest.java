package com.revolutclone.payment.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AccountOpenedV1;
import com.revolutclone.payment.readmodel.AccountView;
import com.revolutclone.payment.readmodel.AccountViewRepository;
import com.revolutclone.payment.readmodel.AccountViewState;
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
 * plans/phase-03-payments-and-ui.md's payment-service design detail: "Account state comes from
 * a local read model in payment-service, built by consuming account.account.v1 - not a
 * synchronous call to account-service ... the compacted topic means a fresh instance rebuilds
 * its view on startup."
 *
 * <p>Drives {@link AccountOpenedEventHandler} directly rather than through a broker, for the
 * reason recorded in docs/spring-boot-4-notes.md: Testcontainers' Kafka module does not start on
 * this machine (Docker Desktop on WSL2 has no real {@code --network=host}). That costs nothing
 * here - the handler is the projection, and the listener contributes only the topic binding and
 * the rebuild seek, which is asserted separately below against the real bean.
 *
 * <p>The replay tests are the ones that matter. They are what licenses this consumer to skip
 * the {@code processed_event} dedupe every other consumer uses: if a second application of the
 * same facts is indistinguishable from the first, replaying the whole compacted topic on every
 * partition assignment is free, and the view becomes self-healing rather than dependent on
 * consumer-group offsets that outlive the database they describe.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000",
        "revolut.payment.fx.base-url=http://fx.invalid"
})
class AccountReadModelProjectionTest {

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
    private AccountOpenedEventHandler handler;

    @Autowired
    private AccountViewRepository accountViews;

    @Autowired
    private AccountOpenedKafkaListener listener;

    @Test
    void anAccountOpenedEventMaterialisesTheAccountLocally() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-03-01T10:15:30Z");

        handler.handle(envelope(accountId, userId, "EUR", occurredAt));

        AccountView view = accountViews.findById(accountId).orElseThrow();
        assertThat(view.getUserId()).isEqualTo(userId);
        assertThat(view.getCurrency()).isEqualTo("EUR");
        assertThat(view.getType()).isEqualTo("WALLET");
        assertThat(view.getState())
                .as("account-service opens every account ACTIVE; AccountOpenedV1 carries no state")
                .isEqualTo(AccountViewState.ACTIVE);
        assertThat(view.isActive()).isTrue();
        assertThat(view.isOwnedBy(userId)).isTrue();
        assertThat(view.isOwnedBy(UUID.randomUUID())).isFalse();
        assertThat(view.getUpdatedAt())
                .as("updated_at is event time, not wall clock")
                .isEqualTo(occurredAt);
    }

    @Test
    void redeliveringTheSameEventLeavesExactlyTheSameSingleRow() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        EventEnvelope<AccountOpenedV1> envelope =
                envelope(accountId, userId, "GBP", Instant.parse("2026-03-02T09:00:00Z"));

        handler.handle(envelope);
        handler.handle(envelope);

        assertThat(accountViews.findByUserId(userId))
                .as("the projection is keyed on accountId, so a redelivery updates rather than inserts")
                .hasSize(1);
        AccountView view = accountViews.findById(accountId).orElseThrow();
        assertThat(view.getCurrency()).isEqualTo("GBP");
        assertThat(view.getUpdatedAt()).isEqualTo(Instant.parse("2026-03-02T09:00:00Z"));
    }

    @Test
    void replayingTheTopicUnderFreshEventIdsStillConvergesOnOneRowPerAccount() {
        // A rebuild after seekToBeginning redelivers the identical records, but a republished
        // account (account-service re-emitting from its own outbox) arrives with a new eventId.
        // Neither may fork the projection: the row is keyed on accountId, not on eventId, which
        // is precisely why no processed_event guard is needed here.
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        handler.handle(envelope(accountId, userId, "USD", Instant.parse("2026-03-03T08:00:00Z")));
        handler.handle(envelope(accountId, userId, "USD", Instant.parse("2026-03-03T08:00:00Z")));

        assertThat(accountViews.findByUserId(userId)).hasSize(1);
        assertThat(accountViews.findById(accountId).orElseThrow().getCurrency()).isEqualTo("USD");
    }

    @Test
    void eachOwnersAccountsAreQueryableByUserId() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-03-04T12:00:00Z");

        handler.handle(envelope(UUID.randomUUID(), owner, "EUR", occurredAt));
        handler.handle(envelope(UUID.randomUUID(), owner, "GBP", occurredAt));
        handler.handle(envelope(UUID.randomUUID(), stranger, "EUR", occurredAt));

        assertThat(accountViews.findByUserId(owner))
                .extracting(AccountView::getCurrency)
                .containsExactlyInAnyOrder("EUR", "GBP");
        assertThat(accountViews.findByUserId(stranger)).hasSize(1);
    }

    @Test
    void theListenerSeeksToTheBeginningOnAssignmentSoAFreshInstanceRebuildsItsView() {
        RecordingSeekCallback callback = new RecordingSeekCallback();
        TopicPartition zero = new TopicPartition("account.account.v1", 0);
        TopicPartition seven = new TopicPartition("account.account.v1", 7);

        listener.onPartitionsAssigned(Map.of(zero, 120L, seven, 4L), callback);

        assertThat(callback.seekedToBeginning)
                .as("every assigned partition must be replayed, not resumed from a committed offset")
                .hasSize(1);
        assertThat(callback.seekedToBeginning.getFirst()).containsExactlyInAnyOrder(zero, seven);
    }

    private static EventEnvelope<AccountOpenedV1> envelope(UUID accountId, UUID userId, String currency,
            Instant occurredAt) {
        return new EventEnvelope<>(
                UUID.randomUUID(),
                occurredAt,
                "corr-" + accountId,
                "AccountOpenedV1",
                1,
                new AccountOpenedV1(accountId, userId, currency, "WALLET"));
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
