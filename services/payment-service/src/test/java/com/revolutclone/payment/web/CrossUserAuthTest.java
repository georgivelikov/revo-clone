package com.revolutclone.payment.web;

import static com.revolutclone.payment.testsupport.AccountViewFixture.activeWallet;
import static com.revolutclone.payment.testsupport.TransferRequests.transferBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.messaging.outbox.OutboxEventRepository;
import com.revolutclone.payment.domain.PaymentRepository;
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
 * plans/phase-03-payments-and-ui.md's Tests table: "Cannot send from an account you do not
 * own." Exercises the check documented on {@link
 * com.revolutclone.payment.web.error.SourceAccountNotFoundException}: the source account
 * comes only from the authenticated JWT subject via {@code @CurrentUser}, never from the
 * request body, and "belongs to somebody else" and "does not exist" deliberately produce the
 * identical 404 rather than a distinguishing 403 - the latter would let this endpoint be used
 * to probe which account ids are real.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000"
})
class CrossUserAuthTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private AccountViewRepository accountViews;

    @Autowired
    private OutboxEventRepository outbox;

    private UUID alice;
    private AccountView bobEur;
    private AccountView mallorysEur;

    @BeforeEach
    void seedTwoOtherPartiesWithEuroWallets() {
        outbox.deleteAll();
        payments.deleteAll();
        accountViews.deleteAll();

        alice = UUID.randomUUID();
        bobEur = activeWallet(accountViews, UUID.randomUUID(), "EUR");
        mallorysEur = activeWallet(accountViews, UUID.randomUUID(), "EUR");
    }

    @Test
    void sendingFromAnAccountTheCallerDoesNotOwnIsA404NotA403() throws Exception {
        // alice authenticates, but names mallory's account as the source - never her own.
        mockMvc.perform(post("/api/payments")
                        .header(PaymentController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(mallorysEur.getAccountId(), bobEur.getAccountId(),
                                1_000L, "EUR", "not mine to spend"))
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Source account not found"))
                .andExpect(jsonPath("$.type").value(
                        "https://revolutclone.com/problems/source-account-not-found"))
                .andExpect(jsonPath("$.detail").value(
                        "No account " + mallorysEur.getAccountId() + " belongs to the authenticated user."));

        // Refused before the transaction starts: no payment row, and - critically - no outbox
        // row. An outbox row is an instruction to move money that nothing downstream
        // second-guesses, so one must never exist for an account the caller never proved they own.
        assertThat(payments.findAll()).isEmpty();
        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    void sendingFromAnAccountThatDoesNotExistAtAllIsTheSame404() throws Exception {
        UUID unknownAccountId = UUID.randomUUID();

        mockMvc.perform(post("/api/payments")
                        .header(PaymentController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(unknownAccountId, bobEur.getAccountId(),
                                1_000L, "EUR", "made up account"))
                        .with(jwt().jwt(builder -> builder.subject(alice.toString()))))
                // Same status and the same problem type as "belongs to somebody else" - a
                // caller who does not own an id cannot tell those two cases apart.
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(
                        "https://revolutclone.com/problems/source-account-not-found"));

        assertThat(payments.findAll()).isEmpty();
        assertThat(outbox.findAll()).isEmpty();
    }
}
