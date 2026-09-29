package com.revolutclone.payment.web;

import static com.revolutclone.payment.testsupport.AccountViewFixture.activeWallet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.payment.domain.Payment;
import com.revolutclone.payment.domain.PaymentRepository;
import com.revolutclone.payment.domain.PaymentStatus;
import com.revolutclone.payment.readmodel.AccountView;
import com.revolutclone.payment.readmodel.AccountViewRepository;
import com.revolutclone.payment.testsupport.NoOpJwtDecoderTestConfig;
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
 * Task 06.6: proves {@code POST /internal/payments} reaches the same
 * {@code PaymentInitiator#initiate} as {@code POST /api/payments} - PENDING, the persisted row,
 * an outbox instruction - but with <strong>no JWT attached</strong> and an explicit {@code userId}
 * in the body instead of a principal. {@code TransferHappyPathTest} already proves the shared
 * behaviour behind the public endpoint in full; this test only proves this endpoint's own two
 * differences: no authentication required, and {@code userId} taken from the body.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000",
        "revolut.payment.fx.base-url=http://fx.invalid"
})
class InternalPaymentControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private AccountViewRepository accountViews;

    private UUID userId;
    private AccountView sourceView;
    private AccountView targetView;

    @BeforeEach
    void seedTwoAccounts() {
        payments.deleteAll();
        accountViews.deleteAll();

        userId = UUID.randomUUID();
        sourceView = activeWallet(accountViews, userId, "EUR");
        targetView = activeWallet(accountViews, userId, "EUR");
    }

    @Test
    void initiatesWithNoJwtAtAllUsingTheExplicitUserIdInTheBody() throws Exception {
        mockMvc.perform(post("/internal/payments")
                        .header(PaymentController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(userId, sourceView.getAccountId(), targetView.getAccountId(), 630L, "EUR",
                                "vault deposit")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amountMinor").value(630L))
                .andExpect(jsonPath("$.currency").value("EUR"));

        assertThat(payments.findAll()).hasSize(1);
        Payment persisted = payments.findAll().getFirst();
        assertThat(persisted.getInitiatorUserId()).isEqualTo(userId);
        assertThat(persisted.getSourceAccountId()).isEqualTo(sourceView.getAccountId());
        assertThat(persisted.getTargetAccountId()).isEqualTo(targetView.getAccountId());
        assertThat(persisted.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void stillRequiresAnIdempotencyKey() throws Exception {
        mockMvc.perform(post("/internal/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(userId, sourceView.getAccountId(), targetView.getAccountId(), 100L, "EUR",
                                null)))
                .andExpect(status().isBadRequest());

        assertThat(payments.findAll()).isEmpty();
    }

    @Test
    void theSameKeyTwiceYieldsOnePaymentOnly() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = body(userId, sourceView.getAccountId(), targetView.getAccountId(), 200L, "EUR", null);

        mockMvc.perform(post("/internal/payments")
                        .header(PaymentController.IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/internal/payments")
                        .header(PaymentController.IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());

        assertThat(payments.findAll()).hasSize(1);
    }

    private static String body(UUID userId, UUID sourceAccountId, UUID targetAccountId, long amountMinor,
            String currency, String reference) {
        String ref = reference == null ? "null" : "\"" + reference + "\"";
        return "{\"userId\":\"" + userId + "\",\"sourceAccountId\":\"" + sourceAccountId
                + "\",\"targetAccountId\":\"" + targetAccountId + "\",\"amountMinor\":" + amountMinor
                + ",\"currency\":\"" + currency + "\",\"reference\":" + ref + "}";
    }
}
