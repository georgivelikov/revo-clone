package com.revolutclone.identity.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revolutclone.identity.testsupport.NoOpJwtDecoderTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Task 01.7 - the reconciliation endpoint is only useful if it is actually restricted to
 * {@code admin}. {@link RegisterRollbackTest} already proves the happy path (an admin-authority
 * JWT can reconcile); this test proves the negative space that a security-relevant endpoint
 * always needs: no token is rejected, and a token that authenticates but lacks the {@code admin}
 * authority (e.g. an ordinary {@code customer}) is rejected too, per
 * {@code SecurityConfig}'s {@code hasAuthority("admin")} restriction on {@code /api/identity/admin/**}.
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
class AdminReconcileAuthorizationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void noTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/identity/admin/reconcile"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerAuthorityIsForbidden() throws Exception {
        mockMvc.perform(post("/api/identity/admin/reconcile")
                        .with(jwt().authorities(new SimpleGrantedAuthority("customer"))))
                .andExpect(status().isForbidden());
    }
}
