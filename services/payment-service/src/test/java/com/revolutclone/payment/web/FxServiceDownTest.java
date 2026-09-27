package com.revolutclone.payment.web;

import static com.revolutclone.payment.testsupport.AccountViewFixture.activeWallet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.messaging.outbox.OutboxEventRepository;
import com.revolutclone.payment.domain.PaymentRepository;
import com.revolutclone.payment.fx.FxQuoteHold;
import com.revolutclone.payment.readmodel.AccountView;
import com.revolutclone.payment.readmodel.AccountViewRepository;
import com.revolutclone.payment.testsupport.FakeFxQuotes;
import com.revolutclone.payment.testsupport.FakeFxQuotesTestConfig;
import com.revolutclone.payment.testsupport.NoOpJwtDecoderTestConfig;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * plans/phase-04-fx-multicurrency.md's Tests table: "Kill fx-service after reserve; reservation
 * expires and funds return." Read from payment-service's own side of that story: fx-service is
 * unreachable, which is exactly what {@link FakeFxQuotes#unavailable} stands in for (the circuit
 * breaker's own open-circuit failure collapses to the identical
 * {@code FxQuoteUnavailableException}, so there is nothing further to prove by actually tripping
 * it here). The one thing payment-service is responsible for is not pretending otherwise: no
 * quote means no transfer accepted, nothing reserved by this service (it holds no balances and
 * never calls the ledger directly), and nothing left half-done for anyone to compensate. Whatever
 * fx-service itself was doing to any reservation it already placed before going down is proven on
 * its own side - {@code QuoteExpiryTest} and {@code ReservationService}'s sweep - not here.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import({NoOpJwtDecoderTestConfig.class, FakeFxQuotesTestConfig.class})
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000",
        "revolut.payment.fx.base-url=http://fx.invalid"
})
class FxServiceDownTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FakeFxQuotes fxQuotes;
    @Autowired
    private PaymentRepository payments;
    @Autowired
    private AccountViewRepository accountViews;
    @Autowired
    private OutboxEventRepository outbox;

    private UUID alice;
    private UUID bob;
    private AccountView aliceEur;
    private AccountView bobGbp;
    private AccountView bobEur;

    @BeforeEach
    void aliceHasEurosAndBobHasWallets() {
        outbox.deleteAll();
        payments.deleteAll();
        accountViews.deleteAll();
        fxQuotes.reset();

        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        aliceEur = activeWallet(accountViews, alice, "EUR");
        bobGbp = activeWallet(accountViews, bob, "GBP");
        bobEur = activeWallet(accountViews, bob, "EUR");
    }

    @Test
    void aCrossCurrencyTransferIsRejectedRatherThanAcceptedWhenFxServiceIsUnreachable() throws Exception {
        fxQuotes.unavailable("could not reach fx-service for a quote: connection refused");

        mockMvc.perform(post("/api/payments")
                        .header(PaymentController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(aliceEur, bobGbp, 10_000L))
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(startsWith("FX_QUOTE_UNAVAILABLE")));

        // No row, no instruction to move money, nothing for anyone to compensate - the request
        // never became a payment at all, exactly like every other synchronous rejection.
        assertThat(payments.findAll()).isEmpty();
        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    void aSameCurrencyTransferStillSucceedsEvenThoughFxServiceIsUnreachable() throws Exception {
        fxQuotes.unavailable("fx-service is down");

        mockMvc.perform(post("/api/payments")
                        .header(PaymentController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(aliceEur, bobEur, 1_000L))
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // A same-currency transfer never asks fx-service anything - the down dependency it
        // doesn't have cannot fail it.
        assertThat(fxQuotes.calls()).isEmpty();
        assertThat(payments.findAll()).hasSize(1);
    }

    @Test
    void aQuoteThatWasObtainedBeforeFxServiceWentDownStillLetsTheTransferComplete() throws Exception {
        // The synchronous half is already over by the time fx-service could go down: this
        // service's own outbox append needs no further HTTP call to fx-service at all, which is
        // the whole reason the phase's compensation table describes the reservation's own expiry
        // as the backstop rather than something payment-service has to detect and react to.
        FxQuoteHold quote = new FxQuoteHold(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "EUR",
                10_000L, "GBP", 8_600L, 8_557L, 43L, 0L, Instant.now().plusSeconds(30));
        fxQuotes.respondWith(quote);

        mockMvc.perform(post("/api/payments")
                        .header(PaymentController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(aliceEur, bobGbp, 10_000L))
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isAccepted());

        assertThat(payments.findAll()).hasSize(1);
        assertThat(outbox.findAll()).hasSize(1);
        assertThat(outbox.findAll().getFirst().getType()).isEqualTo("FxExecutionRequestedV1");
    }

    private String transferBody(AccountView source, AccountView target, long amountMinor) {
        return """
                {"sourceAccountId":"%s","targetAccountId":"%s","amountMinor":%d,\
                "currency":"%s","reference":"lunch"}"""
                .formatted(source.getAccountId(), target.getAccountId(), amountMinor, source.getCurrency());
    }
}
