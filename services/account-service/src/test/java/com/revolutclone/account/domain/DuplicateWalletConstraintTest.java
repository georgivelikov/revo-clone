package com.revolutclone.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.revolutclone.account.testsupport.NoOpJwtDecoderTestConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * plans/phase-01-identity-account-gateway.md's Tests table: "Two WALLET rows for the same user
 * and currency are rejected by the DB - proves the name default actually closes the NULL gap."
 * A real database-level constraint test (Testcontainers Postgres), not a service-layer one:
 * the point is that {@code unique(user_id, currency, type, name)} itself rejects the second
 * row, independent of any application code that might otherwise re-check first.
 */
@Testcontainers
@SpringBootTest
@Import(NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        // No real broker in this test - the consumer would otherwise spend the whole test
        // retrying a connection nothing is listening on.
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000"
})
class DuplicateWalletConstraintTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private AccountRepository repository;

    @Test
    void secondWalletRowForSameUserAndCurrencyIsRejectedByTheDatabase() {
        UUID userId = UUID.randomUUID();
        Account first = new Account(UUID.randomUUID(), userId, "EUR", AccountType.WALLET, "", AccountState.ACTIVE);
        repository.saveAndFlush(first);

        Account duplicate =
                new Account(UUID.randomUUID(), userId, "EUR", AccountType.WALLET, "", AccountState.ACTIVE);

        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void differentCurrenciesForTheSameUserAreBothAllowed() {
        UUID userId = UUID.randomUUID();
        repository.saveAndFlush(new Account(UUID.randomUUID(), userId, "EUR", AccountType.WALLET, "", AccountState.ACTIVE));
        repository.saveAndFlush(new Account(UUID.randomUUID(), userId, "GBP", AccountType.WALLET, "", AccountState.ACTIVE));

        assertThat(repository.findByUserId(userId)).hasSize(2);
    }
}
