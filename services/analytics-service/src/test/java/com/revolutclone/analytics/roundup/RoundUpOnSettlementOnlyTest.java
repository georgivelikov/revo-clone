package com.revolutclone.analytics.roundup;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.analytics.consumer.AuthorizationApprovedEventHandler;
import com.revolutclone.analytics.consumer.AuthorizationReversedEventHandler;
import com.revolutclone.analytics.testsupport.CardAuthorizationEventFixtures;
import com.revolutclone.analytics.testsupport.FakeAnalyticsClientsTestConfig;
import com.revolutclone.analytics.testsupport.FakePaymentInitiationClient;
import com.revolutclone.analytics.testsupport.NoOpJwtDecoderTestConfig;
import com.revolutclone.analytics.vault.Vault;
import com.revolutclone.analytics.vault.VaultRepository;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AuthorizationReversedV1;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * plans/phase-06-vaults-analytics.md's Tests table: "An authorization alone triggers nothing; a
 * reversal never rounds up." Round-up is wired into
 * {@link com.revolutclone.analytics.consumer.AuthorizationSettledEventHandler} only - never into
 * {@link AuthorizationApprovedEventHandler} or {@link AuthorizationReversedEventHandler} - so
 * proving this is really just proving that calling either of those two handlers produces no call
 * to {@link com.revolutclone.analytics.payment.PaymentInitiationClient} at all, however large the
 * amount involved, and however many enabled vaults would otherwise have matched.
 *
 * <p>The rationale, restated from the phase: an approval only names a hold, and a hold can still
 * reverse - rounding up against it would round up a transaction that never actually happened.
 */
@Testcontainers
@SpringBootTest
@Import({NoOpJwtDecoderTestConfig.class, FakeAnalyticsClientsTestConfig.class})
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000",
        "revolut.analytics.account.base-url=http://account.invalid",
        "revolut.analytics.payment.base-url=http://payment.invalid"
})
class RoundUpOnSettlementOnlyTest {

    private static final String MERCHANT = "Tesco Express";
    private static final String MCC = "5411";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private AuthorizationApprovedEventHandler approvedHandler;

    @Autowired
    private AuthorizationReversedEventHandler reversedHandler;

    @Autowired
    private VaultRepository vaults;

    @Autowired
    private FakePaymentInitiationClient paymentClient;

    @BeforeEach
    void resetState() {
        vaults.deleteAll();
        paymentClient.reset();
    }

    @Test
    void anApprovalAloneNeverTriggersARoundUp() {
        UUID userId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();
        vaults.save(new Vault(UUID.randomUUID(), userId, UUID.randomUUID(), "Round-ups", "EUR", null, true, cardId));

        approvedHandler.handle(CardAuthorizationEventFixtures.approved(authorizationId, cardId, userId, accountId,
                MERCHANT, MCC, 437L, "EUR", Instant.parse("2024-06-01T08:00:00Z")));

        assertThat(paymentClient.calls()).isEmpty();
    }

    @Test
    void aReversalAfterApprovalNeverTriggersARoundUpEither() {
        UUID userId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();
        vaults.save(new Vault(UUID.randomUUID(), userId, UUID.randomUUID(), "Round-ups", "EUR", null, true, cardId));

        approvedHandler.handle(CardAuthorizationEventFixtures.approved(authorizationId, cardId, userId, accountId,
                MERCHANT, MCC, 437L, "EUR", Instant.parse("2024-06-01T08:00:00Z")));
        reversedHandler.handle(new EventEnvelope<>(UUID.randomUUID(), Instant.parse("2024-06-01T09:00:00Z"),
                "corr-reverse", "AuthorizationReversedV1", 1,
                new AuthorizationReversedV1(authorizationId, cardId, userId, accountId, "4242", "net-ref", MERCHANT,
                        437L, "EUR")));

        assertThat(paymentClient.calls()).isEmpty();
    }
}
