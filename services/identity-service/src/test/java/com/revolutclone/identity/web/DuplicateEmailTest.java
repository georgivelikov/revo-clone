package com.revolutclone.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.identity.testsupport.NoOpJwtDecoderTestConfig;
import com.revolutclone.security.api.IdentityProviderPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * plans/phase-01-identity-account-gateway.md's Tests table: second registration with the same
 * email returns 409, creates no provider user. Runs against
 * {@link com.revolutclone.security.api.InMemoryIdentityAdapter} (selected by
 * {@code revolut.identity.provider=in-memory} below) rather than a Keycloak Testcontainer, per
 * the phase's own Risks &amp; gotchas guidance - this test asserts controller/service
 * behaviour, not the provider adapter itself (that's
 * {@code IdentityProviderPortContractTest}'s job).
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "revolut.identity.provider=in-memory",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "revolut.messaging.outbox.poll-delay-ms=300000"
})
class DuplicateEmailTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IdentityProviderPort identityProviderPort;

    @Test
    void secondRegistrationWithSameEmailIsRejectedAndCreatesNoProviderUser() throws Exception {
        String email = "carol+" + System.nanoTime() + "@demo.io";
        String body = """
                {"email":"%s","password":"a-strong-password","firstName":"Carol","lastName":"Smith","country":"IE"}
                """.formatted(email);

        mockMvc.perform(post("/api/identity/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        int providerUsersAfterFirst = identityProviderPort.listAll(0, 1000).size();

        mockMvc.perform(post("/api/identity/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());

        assertThat(identityProviderPort.listAll(0, 1000))
                .as("a rejected duplicate registration must create no provider user")
                .hasSize(providerUsersAfterFirst);
    }
}
