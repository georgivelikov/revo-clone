package com.revolutclone.account.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.account.domain.AccountRepository;
import com.revolutclone.account.domain.AccountType;
import com.revolutclone.account.testsupport.NoOpJwtDecoderTestConfig;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.UserRegisteredV1;
import com.revolutclone.messaging.idempotent.IdempotentHandler;
import com.revolutclone.messaging.idempotent.IdempotentHandlerFactory;
import java.time.Instant;
import java.util.List;
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
 * plans/phase-01-identity-account-gateway.md's Tests table: "One event -&gt; three wallets;
 * same event twice -&gt; still three wallets." The important redelivery case -
 * plans/phase-01-identity-account-gateway.md calls this out as "the first real proof the
 * idempotent-consumer wrapper from Phase 00 actually works."
 *
 * <p>Drives {@link UserRegisteredEventHandler} directly through the same
 * {@link IdempotentHandlerFactory} wrapping {@link UserRegisteredKafkaListener} uses, rather
 * than through a real broker - exactly the point of splitting {@code EventHandler} out from the
 * {@code @KafkaListener} adapter (see common-messaging's module javadoc): the business logic
 * and its idempotency are unit-testable with no broker at all. This also sidesteps a real
 * environment limitation recorded in docs/spring-boot-4-notes.md: Testcontainers' Kafka module
 * does not start on this machine (Docker Desktop on WSL2 lacks the real {@code --network=host}
 * both its container classes need).
 */
@Testcontainers
@SpringBootTest
@Import(NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000"
})
class UserRegisteredConsumerTest {

    private static final List<String> STARTER_CURRENCIES = List.of("EUR", "GBP", "USD");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private UserRegisteredEventHandler delegate;

    @Autowired
    private IdempotentHandlerFactory idempotentHandlerFactory;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void oneEventOpensThreeWalletsAndRedeliveryCreatesNoDuplicates() {
        IdempotentHandler<UserRegisteredV1> handler = idempotentHandlerFactory.wrap(delegate, "account-service");
        UUID userId = UUID.randomUUID();
        EventEnvelope<UserRegisteredV1> envelope = new EventEnvelope<>(
                UUID.randomUUID(),
                Instant.now(),
                "corr-consumer-test",
                "UserRegisteredV1",
                1,
                new UserRegisteredV1(userId, "wallet-test@demo.io", "Wal", "Let", "IE"));

        handler.handle(envelope);

        assertThat(accountRepository.findByUserId(userId))
                .as("one delivery must open exactly the three starter wallets")
                .hasSize(3)
                .extracting(a -> a.getCurrency())
                .containsExactlyInAnyOrderElementsOf(STARTER_CURRENCIES);
        assertThat(accountRepository.findByUserId(userId))
                .allSatisfy(a -> assertThat(a.getType()).isEqualTo(AccountType.WALLET));

        // Same eventId, delivered again - the IdempotentHandler must treat this as already
        // done and never call the delegate a second time.
        handler.handle(envelope);

        assertThat(accountRepository.findByUserId(userId))
                .as("redelivery of the same event must create no duplicate wallets")
                .hasSize(3);
    }

    @Test
    void differentUsersEachGetTheirOwnThreeWallets() {
        IdempotentHandler<UserRegisteredV1> handler = idempotentHandlerFactory.wrap(delegate, "account-service");
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        handler.handle(envelopeFor(userA, "a@demo.io"));
        handler.handle(envelopeFor(userB, "b@demo.io"));

        assertThat(accountRepository.findByUserId(userA)).hasSize(3);
        assertThat(accountRepository.findByUserId(userB)).hasSize(3);
    }

    private static EventEnvelope<UserRegisteredV1> envelopeFor(UUID userId, String email) {
        return new EventEnvelope<>(
                UUID.randomUUID(), Instant.now(), "corr-" + userId, "UserRegisteredV1", 1,
                new UserRegisteredV1(userId, email, "First", "Last", "IE"));
    }
}
