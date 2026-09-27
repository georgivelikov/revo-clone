package com.revolutclone.account.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.account.domain.Account;
import com.revolutclone.account.domain.AccountRepository;
import com.revolutclone.account.domain.AccountState;
import com.revolutclone.account.domain.AccountType;
import com.revolutclone.account.testsupport.NoOpJwtDecoderTestConfig;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.LedgerAccountCreatedV1;
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
 * plans/phase-01-identity-account-gateway.md's own words for {@code Account.ledgerAccountId}:
 * "Filled in by Phase 02 once the ledger exists." This is the consumer that actually does that -
 * see {@link LedgerAccountCreatedEventHandler}'s own javadoc for why it did not exist until now
 * and why fixing it belongs here rather than being deferred.
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
class LedgerAccountCreatedConsumerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private LedgerAccountCreatedEventHandler handler;

    @Autowired
    private AccountRepository accounts;

    @Test
    void theEventFillsInLedgerAccountIdOnTheMatchingAccount() {
        UUID accountId = UUID.randomUUID();
        accounts.saveAndFlush(new Account(accountId, UUID.randomUUID(), "EUR", AccountType.WALLET, "",
                AccountState.ACTIVE));
        UUID ledgerAccountId = UUID.randomUUID();

        handler.handle(envelope(new LedgerAccountCreatedV1(accountId, ledgerAccountId)));

        assertThat(accounts.findById(accountId).orElseThrow().getLedgerAccountId()).isEqualTo(ledgerAccountId);
    }

    @Test
    void reapplyingTheSameEventIsHarmless() {
        UUID accountId = UUID.randomUUID();
        accounts.saveAndFlush(new Account(accountId, UUID.randomUUID(), "GBP", AccountType.WALLET, "",
                AccountState.ACTIVE));
        UUID ledgerAccountId = UUID.randomUUID();
        EventEnvelope<LedgerAccountCreatedV1> event = envelope(
                new LedgerAccountCreatedV1(accountId, ledgerAccountId));

        handler.handle(event);
        handler.handle(event);

        assertThat(accounts.findById(accountId).orElseThrow().getLedgerAccountId()).isEqualTo(ledgerAccountId);
    }

    @Test
    void anEventForAnAccountThisServiceHasNeverHeardOfIsIgnoredRatherThanThrowing() {
        handler.handle(envelope(new LedgerAccountCreatedV1(UUID.randomUUID(), UUID.randomUUID())));
        // No exception is the assertion - see the handler's own javadoc for why this is not an
        // ambiguous case worth failing over.
    }

    private static EventEnvelope<LedgerAccountCreatedV1> envelope(LedgerAccountCreatedV1 payload) {
        return new EventEnvelope<>(UUID.randomUUID(), Instant.now(), UUID.randomUUID().toString(),
                "LedgerAccountCreatedV1", 1, payload);
    }
}
