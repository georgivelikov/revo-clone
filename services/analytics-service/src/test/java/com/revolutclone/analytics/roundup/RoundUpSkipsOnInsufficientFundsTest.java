package com.revolutclone.analytics.roundup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.revolutclone.analytics.categorisation.Category;
import com.revolutclone.analytics.consumer.AuthorizationSettledEventHandler;
import com.revolutclone.analytics.payment.PaymentRejectedException;
import com.revolutclone.analytics.readmodel.CardAuthorizationFact;
import com.revolutclone.analytics.readmodel.CardAuthorizationFactRepository;
import com.revolutclone.analytics.spend.MonthlySpend;
import com.revolutclone.analytics.spend.MonthlySpendRepository;
import com.revolutclone.analytics.testsupport.CardAuthorizationEventFixtures;
import com.revolutclone.analytics.testsupport.FakeAnalyticsClientsTestConfig;
import com.revolutclone.analytics.testsupport.FakePaymentInitiationClient;
import com.revolutclone.analytics.testsupport.NoOpJwtDecoderTestConfig;
import com.revolutclone.analytics.vault.Vault;
import com.revolutclone.analytics.vault.VaultRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
 * plans/phase-06-vaults-analytics.md's Tests table: "Overdrawing round-up is skipped; the card
 * settlement is unaffected".
 *
 * <p><strong>Why this test cannot simulate real insufficient funds, and what it simulates
 * instead.</strong> {@code payment-service}'s {@code PaymentRejectionReason} enum has no
 * {@code INSUFFICIENT_FUNDS} value: whether a wallet can afford a transfer is discovered
 * asynchronously, after {@code POST /internal/payments} has already accepted the request
 * (202/PENDING), once ledger-service itself rejects the posting. There is no synchronous balance
 * check anywhere outside ledger-service, and analytics-service is not permitted to call the ledger
 * directly (established in task 06.6) - so there is no way for this test, or for
 * {@link RoundUpService} itself, to observe "this would overdraw" before attempting the transfer.
 *
 * <p>What <em>is</em> observable, and what this test exercises, is the one synchronous failure
 * this system can produce at the initiation boundary: {@link PaymentRejectedException} thrown by
 * {@link com.revolutclone.analytics.payment.PaymentInitiationClient#initiate}, exactly as it would
 * be thrown for a frozen wallet, an unknown account, or (via {@code payment-service}'s own later,
 * asynchronous rejection surfacing as a synchronous one for the purposes of this fake) insufficient
 * funds. The behaviour under test is not "insufficient funds is detected" - it cannot be, from
 * here - but "whatever synchronous failure the initiation call produces, the round-up attempt never
 * escapes it and the settlement's primary projections land regardless". That is the actual
 * behaviour {@link RoundUpService#applyRoundUp} implements, and the actual guarantee
 * "skip silently rather than failing" gives in this architecture.
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
class RoundUpSkipsOnInsufficientFundsTest {

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

    @Autowired
    private CardAuthorizationFactRepository facts;

    @Autowired
    private MonthlySpendRepository monthlySpend;

    @BeforeEach
    void resetState() {
        vaults.deleteAll();
        paymentClient.reset();
    }

    @Test
    void aRoundUpRejectedAtInitiationIsSkippedAndTheSettlementIsStillFullyProjected() {
        UUID userId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID walletAccountId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();
        Instant settledAt = Instant.parse("2024-06-01T08:00:00Z");
        vaults.save(new Vault(UUID.randomUUID(), userId, UUID.randomUUID(), "Round-ups", "EUR", null, true, cardId));

        paymentClient.failNextCallWith(new PaymentRejectedException("insufficient funds"));

        // The whole point: the settlement handler must not throw, even though the round-up it
        // triggers is rejected underneath it.
        assertThatCode(() -> settledHandler.handle(CardAuthorizationEventFixtures.settled(authorizationId, cardId,
                userId, walletAccountId, MERCHANT, MCC, 437L, "EUR", settledAt))).doesNotThrowAnyException();

        // The round-up was genuinely attempted (this is what makes the rejection meaningful)...
        assertThat(paymentClient.calls()).hasSize(1);

        // ...but the settlement's primary projections land exactly as if the round-up had
        // succeeded, or had never been enabled at all.
        CardAuthorizationFact fact = facts.findById(authorizationId).orElseThrow();
        assertThat(fact.getState()).isEqualTo("SETTLED");
        assertThat(fact.getSettledMinor()).isEqualTo(437L);
        assertThat(fact.getSettledAt()).isEqualTo(settledAt);

        LocalDate month = LocalDate.of(2024, 6, 1);
        List<MonthlySpend> bucket = monthlySpend.findByUserIdAndMonthAndCategory(userId, month, Category.OTHER);
        assertThat(bucket).hasSize(1);
        assertThat(bucket.getFirst().getCurrency()).isEqualTo("EUR");
        assertThat(bucket.getFirst().getTotalMinor()).isEqualTo(437L);
        assertThat(bucket.getFirst().getTxnCount()).isEqualTo(1);
    }
}
