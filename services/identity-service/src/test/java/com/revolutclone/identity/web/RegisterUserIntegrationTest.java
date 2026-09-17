package com.revolutclone.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.identity.domain.UserProfile;
import com.revolutclone.identity.domain.UserProfileRepository;
import com.revolutclone.identity.testsupport.NoOpJwtDecoderTestConfig;
import com.revolutclone.messaging.outbox.OutboxEvent;
import com.revolutclone.messaging.outbox.OutboxEventRepository;
import com.revolutclone.security.api.IdentityProviderPort;
import com.revolutclone.security.keycloak.KeycloakIdentityAdapter;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * plans/phase-01-identity-account-gateway.md's Tests table: "Testcontainers + Keycloak
 * container: register -&gt; profile row + outbox row in one transaction." The Keycloak
 * Testcontainer is shared and static (~15s startup, per the phase's Risks &amp; gotchas) and
 * managed against its default {@code master} realm via the admin CLI grant, the same pattern
 * {@code IdentityProviderPortContractTest} (phase 00 task 8) already uses - the port's contract
 * does not depend on which realm or grant type authenticated the admin client, so there is no
 * need to also stand up the {@code revolut-backend} confidential client here.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import({NoOpJwtDecoderTestConfig.class, RegisterUserIntegrationTest.KeycloakAdapterConfig.class})
@TestPropertySource(properties = {
        "revolut.identity.provider=in-memory",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "revolut.messaging.outbox.poll-delay-ms=300000"
})
class RegisterUserIntegrationTest {

    private static final String REALM = "master";
    private static final String CUSTOMER_ROLE = "customer";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Container
    static KeycloakContainer keycloak = new KeycloakContainer("quay.io/keycloak/keycloak:26.7");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeAll
    static void ensureCustomerRoleExists() {
        Keycloak admin = keycloak.getKeycloakAdminClient();
        RoleRepresentation role = new RoleRepresentation();
        role.setName(CUSTOMER_ROLE);
        admin.realm(REALM).roles().create(role);
    }

    @Test
    void registerCreatesAProfileRowAndAnOutboxRowInOneTransaction() throws Exception {
        String email = "keycloak-e2e+" + System.nanoTime() + "@demo.io";
        String body = """
                {"email":"%s","password":"a-strong-password","firstName":"Kay","lastName":"Cloak","country":"IE"}
                """.formatted(email);

        mockMvc.perform(post("/api/identity/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        UserProfile profile = userProfileRepository.findByEmailIgnoreCase(email).orElseThrow();
        List<OutboxEvent> outboxRows = outboxEventRepository.findAll().stream()
                .filter(row -> row.getPartitionKey().equals(profile.getId().toString()))
                .toList();

        assertThat(outboxRows)
                .as("registration must append exactly one UserRegisteredV1 outbox row, keyed by userId")
                .hasSize(1);
        assertThat(outboxRows.get(0).getTopic()).isEqualTo("identity.user.v1");
        assertThat(outboxRows.get(0).getType()).isEqualTo("UserRegisteredV1");
    }

    @TestConfiguration
    static class KeycloakAdapterConfig {

        @Bean
        @Primary
        IdentityProviderPort keycloakBackedIdentityProviderPort() {
            return new KeycloakIdentityAdapter(keycloak.getKeycloakAdminClient(), REALM);
        }
    }
}
