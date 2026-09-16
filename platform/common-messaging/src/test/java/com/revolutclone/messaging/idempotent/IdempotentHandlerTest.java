package com.revolutclone.messaging.idempotent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.UserRegisteredV1;
import com.revolutclone.messaging.api.EventHandler;
import com.revolutclone.messaging.testsupport.TestMessagingApplication;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The same {@code eventId} delivered twice must produce exactly one side effect. */
@Testcontainers
@SpringBootTest(classes = TestMessagingApplication.class)
class IdempotentHandlerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private IdempotentHandlerFactory factory;

    @Test
    void sameEventIdDeliveredTwiceProducesOneSideEffect() {
        AtomicInteger sideEffects = new AtomicInteger();
        EventHandler<UserRegisteredV1> delegate = envelope -> sideEffects.incrementAndGet();
        IdempotentHandler<UserRegisteredV1> handler = factory.wrap(delegate, "test-consumer-group");

        EventEnvelope<UserRegisteredV1> envelope = new EventEnvelope<>(
                UUID.randomUUID(),
                Instant.now(),
                "corr-1",
                "UserRegisteredV1",
                1,
                new UserRegisteredV1(UUID.randomUUID(), "a@b.com", "A", "B", "GB"));

        handler.handle(envelope);
        assertThatCode(() -> handler.handle(envelope)).doesNotThrowAnyException();

        assertThat(sideEffects.get()).isEqualTo(1);
    }

    @Test
    void differentConsumerGroupsProcessTheSameEventIndependently() {
        AtomicInteger sideEffects = new AtomicInteger();
        EventHandler<UserRegisteredV1> delegate = envelope -> sideEffects.incrementAndGet();

        EventEnvelope<UserRegisteredV1> envelope = new EventEnvelope<>(
                UUID.randomUUID(),
                Instant.now(),
                "corr-2",
                "UserRegisteredV1",
                1,
                new UserRegisteredV1(UUID.randomUUID(), "c@d.com", "C", "D", "GB"));

        factory.wrap(delegate, "group-a").handle(envelope);
        factory.wrap(delegate, "group-b").handle(envelope);

        assertThat(sideEffects.get()).isEqualTo(2);
    }
}
