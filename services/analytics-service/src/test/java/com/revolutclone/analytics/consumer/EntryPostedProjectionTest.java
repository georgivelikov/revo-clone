package com.revolutclone.analytics.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.analytics.readmodel.LedgerEntryFact;
import com.revolutclone.analytics.readmodel.LedgerEntryFactRepository;
import com.revolutclone.analytics.testsupport.NoOpJwtDecoderTestConfig;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.EntryPostedV1;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * plans/phase-06-vaults-analytics.md: "drop its entire database, replay the topics from offset
 * 0, and get byte-identical results" is the test of a correctly-built projection. This is the
 * narrower unit-level version for task 06.1's own scope: one {@code EntryPostedV1} produces
 * exactly one {@link LedgerEntryFact} row, bucketed on the event's own {@code occurredAt}
 * (never {@code Instant.now()}), and redelivering the same event leaves that row unchanged -
 * the projection is a pure function of the event, not of when it happened to be applied.
 *
 * <p>Drives {@link EntryPostedEventHandler} directly rather than through a real broker, exactly
 * as {@code UserRegisteredConsumerTest} does for the identical reason: the business logic is
 * unit-testable with no broker at all, and Testcontainers' Kafka module does not start on this
 * machine anyway (docs/spring-boot-4-notes.md).
 */
@Testcontainers
@SpringBootTest
@Import(NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class EntryPostedProjectionTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private EntryPostedEventHandler handler;

    @Autowired
    private LedgerEntryFactRepository facts;

    @Test
    void aPostedEntryIsRecordedBucketedOnItsOwnOccurredAt() {
        UUID entryId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        // Two years in the past - a projection that read the wall clock instead would land this
        // somewhere else entirely, which is exactly what NoWallClockInProjectionTest (task 06.3)
        // will assert against monthly_spend once that table has a writer.
        Instant historicalOccurredAt = Instant.parse("2024-01-15T10:30:00Z");

        handler.handle(envelope(entryId, new EntryPostedV1(entryId, accountId, "DEBIT", -4_250L, "EUR", 55_750L,
                "TRANSFER", "payment:" + UUID.randomUUID()), historicalOccurredAt));

        LedgerEntryFact fact = facts.findById(entryId).orElseThrow();
        assertThat(fact.getAccountId()).isEqualTo(accountId);
        assertThat(fact.getDirection()).isEqualTo("DEBIT");
        assertThat(fact.getAmountMinor()).isEqualTo(-4_250L);
        assertThat(fact.getCurrency()).isEqualTo("EUR");
        assertThat(fact.getBalanceAfterMinor()).isEqualTo(55_750L);
        assertThat(fact.getType()).isEqualTo("TRANSFER");
        assertThat(fact.getOccurredAt()).isEqualTo(historicalOccurredAt);
    }

    @Test
    void redeliveryOfTheSameEntryLeavesExactlyOneUnchangedRow() {
        UUID entryId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2024-03-01T00:00:00Z");
        EntryPostedV1 payload = new EntryPostedV1(entryId, accountId, "CREDIT", 1_000L, "GBP", 1_000L,
                "ADJUSTMENT", null);

        handler.handle(envelope(entryId, payload, occurredAt));
        // Redelivered under a fresh eventId, as a real replay would - only entryId (the payload's
        // own identity) is the upsert key, so this must not attempt a second insert under the same
        // primary key.
        handler.handle(envelope(entryId, payload, occurredAt));

        assertThat(facts.findById(entryId)).isPresent();
        assertThat(facts.findById(entryId).orElseThrow().getAmountMinor()).isEqualTo(1_000L);
    }

    private static EventEnvelope<EntryPostedV1> envelope(UUID entryId, EntryPostedV1 payload, Instant occurredAt) {
        return new EventEnvelope<>(UUID.randomUUID(), occurredAt, "corr-" + entryId, "EntryPostedV1", 1, payload);
    }
}
