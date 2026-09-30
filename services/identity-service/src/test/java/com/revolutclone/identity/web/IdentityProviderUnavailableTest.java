package com.revolutclone.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.identity.domain.UserProfileRepository;
import com.revolutclone.identity.testsupport.NoOpJwtDecoderTestConfig;
import com.revolutclone.security.api.IdentityProviderPort;
import com.revolutclone.security.api.InMemoryIdentityAdapter;
import com.revolutclone.security.api.NewUser;
import com.revolutclone.security.api.ProviderUser;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * plans/phase-07-resilience-and-scale.md's Circuit breakers table, the {@code identity ->
 * keycloak} row: registration must fail fast with a clear message when the identity provider is
 * unreachable, and must never leave a half-created user behind. {@link FailingIdentityProviderPort}
 * stands in for a Keycloak outage at each of the two points {@code RegistrationService} calls
 * through {@link IdentityProviderPort} - exactly as {@code FxServiceDownTest} stands in for
 * fx-service being unreachable on the payment -> fx hop.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import({NoOpJwtDecoderTestConfig.class, IdentityProviderUnavailableTest.FailureInjectionConfig.class})
@TestPropertySource(properties = {
        "revolut.identity.provider=in-memory",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "revolut.messaging.outbox.poll-delay-ms=300000"
})
class IdentityProviderUnavailableTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private FailingIdentityProviderPort identityProviderPort;

    @Test
    void registrationFailsFastWithAClearMessageWhenTheProviderCannotBeReachedAtAll() throws Exception {
        identityProviderPort.failCreate.set(true);
        String email = "unreachable+" + System.nanoTime() + "@demo.io";

        mockMvc.perform(post("/api/identity/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("could not register through the identity provider")));

        assertThat(userProfileRepository.findByEmailIgnoreCase(email)).isEmpty();
        assertThat(identityProviderPort.deleteCalls.get())
                .as("nothing was ever created, so there is nothing to compensate")
                .isZero();
    }

    @Test
    void aProviderUserCreatedJustBeforeTheProviderGoesDownMidRegistrationIsDeletedRatherThanLeftHalfCreated()
            throws Exception {
        identityProviderPort.failSetPassword.set(true);
        String email = "half-created+" + System.nanoTime() + "@demo.io";

        mockMvc.perform(post("/api/identity/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email)))
                .andExpect(status().isServiceUnavailable());

        assertThat(userProfileRepository.findByEmailIgnoreCase(email)).isEmpty();
        assertThat(identityProviderPort.delegate.findByEmail(email))
                .as("the half-created provider user (created, but with no password or role) must be" +
                        " cleaned up rather than left behind")
                .isEmpty();
        assertThat(identityProviderPort.deleteCalls.get()).isOne();
    }

    private static String registerBody(String email) {
        return """
                {"email":"%s","password":"a-strong-password","firstName":"Dana","lastName":"Down","country":"IE"}
                """.formatted(email);
    }

    @TestConfiguration
    static class FailureInjectionConfig {

        @Bean
        @Primary
        FailingIdentityProviderPort failingIdentityProviderPort() {
            return new FailingIdentityProviderPort(new InMemoryIdentityAdapter());
        }
    }

    /**
     * Fails at whichever provisioning step a test arms, exactly once, mirroring a Keycloak
     * outage that starts partway through {@code RegistrationService#provisionInKeycloak}.
     */
    static class FailingIdentityProviderPort implements IdentityProviderPort {

        final AtomicBoolean failCreate = new AtomicBoolean(false);
        final AtomicBoolean failSetPassword = new AtomicBoolean(false);
        final AtomicInteger deleteCalls = new AtomicInteger(0);
        final IdentityProviderPort delegate;

        FailingIdentityProviderPort(IdentityProviderPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public ProviderUser create(NewUser user) {
            if (failCreate.getAndSet(false)) {
                throw new RuntimeException("simulated Keycloak outage: connection refused");
            }
            return delegate.create(user);
        }

        @Override
        public void setPassword(String providerUserId, char[] password) {
            if (failSetPassword.getAndSet(false)) {
                throw new RuntimeException("simulated Keycloak outage mid-registration");
            }
            delegate.setPassword(providerUserId, password);
        }

        @Override
        public void assignRole(String providerUserId, String role) {
            delegate.assignRole(providerUserId, role);
        }

        @Override
        public Optional<ProviderUser> findByEmail(String email) {
            return delegate.findByEmail(email);
        }

        @Override
        public void delete(String providerUserId) {
            deleteCalls.incrementAndGet();
            delegate.delete(providerUserId);
        }

        @Override
        public List<ProviderUser> listAll(int page, int size) {
            return delegate.listAll(page, size);
        }
    }
}
