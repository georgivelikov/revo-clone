package com.revolutclone.analytics.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.analytics.readmodel.CardAuthorizationFact;
import com.revolutclone.analytics.readmodel.CardAuthorizationFactRepository;
import com.revolutclone.analytics.testsupport.NoOpJwtDecoderTestConfig;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AuthorizationApprovedV1;
import com.revolutclone.contracts.events.AuthorizationExpiredV1;
import com.revolutclone.contracts.events.AuthorizationReversedV1;
import com.revolutclone.contracts.events.AuthorizationSettledV1;
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
 * Exercises the four lifecycle handlers {@link CardAuthorizationKafkaListener} routes to,
 * proving they converge on one row per authorization the way plans/phase-06-vaults-analytics.md's
 * "drop the DB, replay from offset 0" test demands of every projection in this service.
 */
@Testcontainers
@SpringBootTest
@Import(NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false"
})
class CardAuthorizationProjectionTest {

    private static final String MERCHANT = "Tesco Express";
    private static final String MCC = "5411";
    private static final String COUNTRY = "IE";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private AuthorizationApprovedEventHandler approvedHandler;

    @Autowired
    private AuthorizationSettledEventHandler settledHandler;

    @Autowired
    private AuthorizationReversedEventHandler reversedHandler;

    @Autowired
    private AuthorizationExpiredEventHandler expiredHandler;

    @Autowired
    private CardAuthorizationFactRepository facts;

    @Test
    void anApprovalRecordsAPendingRowWithTheMerchantAndBillingDetails() {
        UUID authorizationId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Instant authorizedAt = Instant.parse("2024-06-01T08:00:00Z");

        approvedHandler.handle(approvedEnvelope(authorizationId, cardId, userId, accountId, authorizedAt));

        CardAuthorizationFact fact = facts.findById(authorizationId).orElseThrow();
        assertThat(fact.getUserId()).isEqualTo(userId);
        assertThat(fact.getCardId()).isEqualTo(cardId);
        assertThat(fact.getAccountId()).isEqualTo(accountId);
        assertThat(fact.getMerchantName()).isEqualTo(MERCHANT);
        assertThat(fact.getMerchantMcc()).isEqualTo(MCC);
        assertThat(fact.getMerchantCountry()).isEqualTo(COUNTRY);
        assertThat(fact.getAmountMinor()).isEqualTo(4_370L);
        assertThat(fact.getCurrency()).isEqualTo("EUR");
        assertThat(fact.getBillingMinor()).isEqualTo(4_370L);
        assertThat(fact.getBillingCurrency()).isEqualTo("EUR");
        assertThat(fact.getState()).isEqualTo(CardAuthorizationFact.APPROVED);
        assertThat(fact.getAuthorizedAt()).isEqualTo(authorizedAt);
        assertThat(fact.getSettledAt()).isNull();
        assertThat(fact.getSettledMinor()).isNull();
    }

    @Test
    void aSettlementAfterApprovalUpdatesTheSameRowToSettled() {
        UUID authorizationId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Instant authorizedAt = Instant.parse("2024-06-01T08:00:00Z");
        Instant settledAt = Instant.parse("2024-06-03T12:00:00Z");

        approvedHandler.handle(approvedEnvelope(authorizationId, cardId, userId, accountId, authorizedAt));
        settledHandler.handle(new EventEnvelope<>(UUID.randomUUID(), settledAt, "corr-settle", "AuthorizationSettledV1",
                1, new AuthorizationSettledV1(authorizationId, cardId, userId, accountId, "4242", "net-ref",
                        MERCHANT, MCC, COUNTRY, 4_370L, "EUR", 4_370L, "EUR", 0L, 0L, UUID.randomUUID(), null,
                        "SETTLED")));

        CardAuthorizationFact fact = facts.findById(authorizationId).orElseThrow();
        assertThat(fact.getState()).isEqualTo("SETTLED");
        assertThat(fact.getSettledMinor()).isEqualTo(4_370L);
        assertThat(fact.getSettledAt()).isEqualTo(settledAt);
        // The approval's own facts survive the update - settlement only adds to the row.
        assertThat(fact.getMerchantName()).isEqualTo(MERCHANT);
        assertThat(fact.getAuthorizedAt()).isEqualTo(authorizedAt);
    }

    @Test
    void aReversalAfterApprovalMovesTheRowToReversedWithNoSettlement() {
        UUID authorizationId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Instant authorizedAt = Instant.parse("2024-06-01T08:00:00Z");
        Instant reversedAt = Instant.parse("2024-06-01T09:00:00Z");

        approvedHandler.handle(approvedEnvelope(authorizationId, cardId, userId, accountId, authorizedAt));
        reversedHandler.handle(new EventEnvelope<>(UUID.randomUUID(), reversedAt, "corr-reverse",
                "AuthorizationReversedV1", 1,
                new AuthorizationReversedV1(authorizationId, cardId, userId, accountId, "4242", "net-ref", MERCHANT,
                        4_370L, "EUR")));

        CardAuthorizationFact fact = facts.findById(authorizationId).orElseThrow();
        assertThat(fact.getState()).isEqualTo(CardAuthorizationFact.REVERSED);
        assertThat(fact.getSettledMinor()).isNull();
        assertThat(fact.getSettledAt()).isNull();
    }

    @Test
    void anExpiryAfterApprovalMovesTheRowToExpiredWithNoSettlement() {
        UUID authorizationId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Instant authorizedAt = Instant.parse("2024-06-01T08:00:00Z");
        Instant expiredAt = Instant.parse("2024-06-08T08:00:01Z");

        approvedHandler.handle(approvedEnvelope(authorizationId, cardId, userId, accountId, authorizedAt));
        expiredHandler.handle(new EventEnvelope<>(UUID.randomUUID(), expiredAt, "corr-expire",
                "AuthorizationExpiredV1", 1,
                new AuthorizationExpiredV1(authorizationId, cardId, userId, accountId, "4242", "net-ref", MERCHANT,
                        4_370L, "EUR")));

        CardAuthorizationFact fact = facts.findById(authorizationId).orElseThrow();
        assertThat(fact.getState()).isEqualTo(CardAuthorizationFact.EXPIRED);
        assertThat(fact.getSettledMinor()).isNull();
    }

    private static EventEnvelope<AuthorizationApprovedV1> approvedEnvelope(UUID authorizationId, UUID cardId,
            UUID userId, UUID accountId, Instant occurredAt) {
        return new EventEnvelope<>(UUID.randomUUID(), occurredAt, "corr-approve", "AuthorizationApprovedV1", 1,
                new AuthorizationApprovedV1(authorizationId, cardId, userId, accountId, "4242", "net-ref", MERCHANT,
                        MCC, COUNTRY, 4_370L, "EUR", 4_370L, "EUR", UUID.randomUUID()));
    }
}
