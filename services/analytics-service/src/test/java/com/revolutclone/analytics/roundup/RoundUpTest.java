package com.revolutclone.analytics.roundup;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.analytics.consumer.AuthorizationSettledEventHandler;
import com.revolutclone.analytics.payment.InitiatePaymentCommand;
import com.revolutclone.analytics.testsupport.CardAuthorizationEventFixtures;
import com.revolutclone.analytics.testsupport.FakeAnalyticsClientsTestConfig;
import com.revolutclone.analytics.testsupport.FakePaymentInitiationClient;
import com.revolutclone.analytics.testsupport.NoOpJwtDecoderTestConfig;
import com.revolutclone.analytics.vault.Vault;
import com.revolutclone.analytics.vault.VaultRepository;
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
 * plans/phase-06-vaults-analytics.md's own worked example, exactly: "4.37 -> 0.63 to vault; 5.00
 * -> no round-up (already whole)". Drives {@link AuthorizationSettledEventHandler} directly -
 * exactly as {@code CardAuthorizationProjectionTest} does for the same event - with a fake
 * payment-service standing in so the assertion can be "the right transfer was requested" rather
 * than "money actually moved", which is payment-service's and ledger-service's own tested
 * responsibility.
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
class RoundUpTest {

    private static final String MERCHANT = "Tesco Express";
    private static final String MCC = "5411";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private AuthorizationSettledEventHandler settledHandler;

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
    void aSettlementThatIsNotAWholeUnitRoundsUpTheDifferenceIntoTheVault() {
        UUID userId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID walletAccountId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();
        Vault vault = vaults.save(
                new Vault(UUID.randomUUID(), userId, UUID.randomUUID(), "Round-ups", "EUR", null, true, cardId));

        settledHandler.handle(CardAuthorizationEventFixtures.settled(authorizationId, cardId, userId,
                walletAccountId, MERCHANT, MCC, 437L, "EUR", Instant.parse("2024-06-01T08:00:00Z")));

        assertThat(paymentClient.calls()).hasSize(1);
        InitiatePaymentCommand command = paymentClient.calls().getFirst();
        assertThat(command.userId()).isEqualTo(userId);
        assertThat(command.sourceAccountId()).isEqualTo(walletAccountId);
        assertThat(command.targetAccountId()).isEqualTo(vault.getAccountId());
        assertThat(command.amountMinor()).isEqualTo(63L);
        assertThat(command.currency()).isEqualTo("EUR");
        // Namespaced by vault id and authorization id - see RoundUpService's own javadoc for why.
        assertThat(command.idempotencyKey()).isEqualTo("roundup:" + vault.getId() + ":" + authorizationId);
    }

    @Test
    void aSettlementThatIsAlreadyAWholeUnitCreatesNoTransferAtAll() {
        UUID userId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        vaults.save(new Vault(UUID.randomUUID(), userId, UUID.randomUUID(), "Round-ups", "EUR", null, true, cardId));

        settledHandler.handle(CardAuthorizationEventFixtures.settled(UUID.randomUUID(), cardId, userId,
                UUID.randomUUID(), MERCHANT, MCC, 500L, "EUR", Instant.parse("2024-06-01T08:00:00Z")));

        assertThat(paymentClient.calls()).isEmpty();
    }

    @Test
    void aVaultWithRoundupsDisabledIsNeverConsidered() {
        UUID userId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        vaults.save(new Vault(UUID.randomUUID(), userId, UUID.randomUUID(), "Disabled", "EUR", null, false, cardId));

        settledHandler.handle(CardAuthorizationEventFixtures.settled(UUID.randomUUID(), cardId, userId,
                UUID.randomUUID(), MERCHANT, MCC, 437L, "EUR", Instant.parse("2024-06-01T08:00:00Z")));

        assertThat(paymentClient.calls()).isEmpty();
    }

    @Test
    void aVaultRoundingUpFromADifferentCardIsNeverConsidered() {
        UUID userId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID otherCardId = UUID.randomUUID();
        vaults.save(
                new Vault(UUID.randomUUID(), userId, UUID.randomUUID(), "Other card", "EUR", null, true, otherCardId));

        settledHandler.handle(CardAuthorizationEventFixtures.settled(UUID.randomUUID(), cardId, userId,
                UUID.randomUUID(), MERCHANT, MCC, 437L, "EUR", Instant.parse("2024-06-01T08:00:00Z")));

        assertThat(paymentClient.calls()).isEmpty();
    }

    @Test
    void twoVaultsRoundingUpFromTheSameCardBothReceiveATransferWithDistinctIdempotencyKeys() {
        UUID userId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();
        Vault first = vaults.save(new Vault(UUID.randomUUID(), userId, UUID.randomUUID(), "Holiday", "EUR", null,
                true, cardId));
        Vault second = vaults.save(new Vault(UUID.randomUUID(), userId, UUID.randomUUID(), "Emergency", "EUR", null,
                true, cardId));

        settledHandler.handle(CardAuthorizationEventFixtures.settled(authorizationId, cardId, userId,
                UUID.randomUUID(), MERCHANT, MCC, 437L, "EUR", Instant.parse("2024-06-01T08:00:00Z")));

        assertThat(paymentClient.calls()).hasSize(2);
        assertThat(paymentClient.calls()).extracting(InitiatePaymentCommand::targetAccountId)
                .containsExactlyInAnyOrder(first.getAccountId(), second.getAccountId());
        assertThat(paymentClient.calls()).extracting(InitiatePaymentCommand::idempotencyKey).containsExactlyInAnyOrder(
                "roundup:" + first.getId() + ":" + authorizationId,
                "roundup:" + second.getId() + ":" + authorizationId);
    }
}
