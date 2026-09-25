package com.revolutclone.ledger.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AccountOpenedV1;
import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.LedgerAccountType;
import com.revolutclone.messaging.idempotent.IdempotentHandler;
import com.revolutclone.messaging.idempotent.IdempotentHandlerFactory;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * plans/phase-02-ledger.md's task list item 8: consuming {@link AccountOpenedV1} creates the
 * matching {@code ledger_account} + zero-balance {@code account_balance}, and a redelivery of
 * the same event creates no duplicate. Drives {@link AccountOpenedEventHandler} directly through
 * the same {@link IdempotentHandlerFactory} wrapping {@link AccountOpenedKafkaListener} uses,
 * mirroring account-service's {@code UserRegisteredConsumerTest} - the business logic and its
 * idempotency are unit-testable with no broker at all, which also sidesteps the real
 * Testcontainers-Kafka environment limitation recorded in docs/spring-boot-4-notes.md.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000"
})
class AccountOpenedConsumerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void oidcIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://127.0.0.1:8180/realms/revolut");
    }

    @Autowired
    private AccountOpenedEventHandler delegate;
    @Autowired
    private IdempotentHandlerFactory idempotentHandlerFactory;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private AccountBalanceRepository accountBalanceRepository;

    @Test
    void consumingAccountOpenedCreatesLedgerAccountAndZeroBalance() {
        IdempotentHandler<AccountOpenedV1> handler = idempotentHandlerFactory.wrap(delegate, "ledger-service");
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        EventEnvelope<AccountOpenedV1> envelope = envelope(accountId, userId, "EUR", "WALLET");

        handler.handle(envelope);

        LedgerAccount ledgerAccount = ledgerAccountRepository
                .findByOwnerRefAndTypeAndCurrency("user:" + userId, LedgerAccountType.USER_WALLET, "EUR")
                .orElseThrow(() -> new AssertionError("no ledger_account created for user " + userId));

        assertThat(ledgerAccount.getOwnerRef()).isEqualTo("user:" + userId);
        assertThat(ledgerAccount.getType()).isEqualTo(LedgerAccountType.USER_WALLET);
        assertThat(ledgerAccount.getCurrency()).isEqualTo("EUR");

        AccountBalance balance = accountBalanceRepository.findById(ledgerAccount.getId())
                .orElseThrow(() -> new AssertionError("no account_balance created for " + ledgerAccount.getId()));
        assertThat(balance.getAvailableMinor()).isZero();
        assertThat(balance.getReservedMinor()).isZero();
        assertThat(balance.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void redeliveryOfTheSameEventCreatesNoDuplicateLedgerAccount() {
        IdempotentHandler<AccountOpenedV1> handler = idempotentHandlerFactory.wrap(delegate, "ledger-service");
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        EventEnvelope<AccountOpenedV1> envelope = envelope(accountId, userId, "GBP", "WALLET");

        handler.handle(envelope);
        // Same eventId, delivered again - the IdempotentHandler must treat this as already done
        // and never call the delegate a second time.
        handler.handle(envelope);

        long matching = ledgerAccountRepository.findAll().stream()
                .filter(a -> a.getOwnerRef().equals("user:" + userId) && a.getCurrency().equals("GBP"))
                .count();
        assertThat(matching).as("redelivery of the same event must create no duplicate ledger_account").isEqualTo(1);
    }

    private static EventEnvelope<AccountOpenedV1> envelope(UUID accountId, UUID userId, String currency,
            String type) {
        return new EventEnvelope<>(
                UUID.randomUUID(), Instant.now(), "corr-" + accountId, "AccountOpenedV1", 1,
                new AccountOpenedV1(accountId, userId, currency, type));
    }
}
