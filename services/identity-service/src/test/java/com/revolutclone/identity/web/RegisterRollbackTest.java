package com.revolutclone.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.contracts.DomainEvent;
import com.revolutclone.identity.domain.UserProfileRepository;
import com.revolutclone.identity.testsupport.NoOpJwtDecoderTestConfig;
import com.revolutclone.messaging.api.OutboxAppender;
import com.revolutclone.messaging.outbox.OutboxAppenderImpl;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * plans/phase-01-identity-account-gateway.md's Tests table: a forced DB failure after the
 * provider user is created leaves no {@code user_profile} row; the reconcile endpoint then
 * back-fills it. The failure is injected at the outbox-append half of {@code UserProfileWriter}'s
 * single local transaction - {@link ToggleableOutboxAppender} throws exactly once, which rolls
 * back the whole transaction (including the {@code user_profile} insert that already ran in the
 * same physical transaction), simulating "step 3 fails" without touching a real DB constraint.
 * Runs against {@code InMemoryIdentityAdapter} per the phase's Risks &amp; gotchas guidance.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import({NoOpJwtDecoderTestConfig.class, RegisterRollbackTest.FailureInjectionConfig.class})
@TestPropertySource(properties = {
        "revolut.identity.provider=in-memory",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "revolut.messaging.outbox.poll-delay-ms=300000"
})
class RegisterRollbackTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private ToggleableOutboxAppender toggleableOutboxAppender;

    @Test
    void failedLocalTransactionLeavesNoProfileAndReconcileBackfillsIt() throws Exception {
        String email = "orphan+" + System.nanoTime() + "@demo.io";
        String body = """
                {"email":"%s","password":"a-strong-password","firstName":"Orphan","lastName":"User","country":"IE"}
                """.formatted(email);

        long profilesBefore = userProfileRepository.count();

        toggleableOutboxAppender.failNext.set(true);
        mockMvc.perform(post("/api/identity/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is5xxServerError());

        assertThat(userProfileRepository.count())
                .as("the failed local transaction must leave no profile behind")
                .isEqualTo(profilesBefore);
        assertThat(userProfileRepository.findByEmailIgnoreCase(email)).isEmpty();

        toggleableOutboxAppender.failNext.set(false);
        mockMvc.perform(post("/api/identity/admin/reconcile")
                        .with(jwt().authorities(new SimpleGrantedAuthority("admin"))))
                .andExpect(status().isOk());

        assertThat(userProfileRepository.findByEmailIgnoreCase(email))
                .as("reconcile must back-fill the orphaned provider user")
                .isPresent();
    }

    @TestConfiguration
    static class FailureInjectionConfig {

        @Bean
        @Primary
        ToggleableOutboxAppender toggleableOutboxAppender(OutboxAppenderImpl delegate) {
            return new ToggleableOutboxAppender(delegate);
        }
    }

    static class ToggleableOutboxAppender implements OutboxAppender {

        final AtomicBoolean failNext = new AtomicBoolean(false);
        private final OutboxAppender delegate;

        ToggleableOutboxAppender(OutboxAppender delegate) {
            this.delegate = delegate;
        }

        @Override
        public void append(String topic, String partitionKey, DomainEvent event) {
            if (failNext.getAndSet(false)) {
                throw new RuntimeException("simulated outbox failure for RegisterRollbackTest");
            }
            delegate.append(topic, partitionKey, event);
        }
    }
}
