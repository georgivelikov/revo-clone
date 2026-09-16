package com.revolutclone.security.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The same JWT read with two different {@code roles-claim} paths must yield the right
 * authorities, and a missing claim must yield none rather than throw.
 */
class RolesClaimMappingTest {

    @Test
    void readsRolesFromTheConfiguredNestedKeycloakPath() {
        Jwt jwt = jwtWithClaims(Map.of("realm_access", Map.of("roles", List.of("customer", "admin"))));

        Collection<GrantedAuthority> authorities =
                new ConfigurableRolesClaimConverter("realm_access.roles").convert(jwt);

        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("customer", "admin");
    }

    @Test
    void readsRolesFromADifferentConfiguredPathOnTheSameToken() {
        Jwt jwt = jwtWithClaims(Map.of(
                "realm_access", Map.of("roles", List.of("customer")),
                "flat_roles", List.of("support")));

        Collection<GrantedAuthority> authorities =
                new ConfigurableRolesClaimConverter("flat_roles").convert(jwt);

        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactly("support");
    }

    @Test
    void aMissingTopLevelClaimYieldsNoAuthoritiesRatherThanThrowing() {
        Jwt jwt = jwtWithClaims(Map.of("sub", "user-1"));

        Collection<GrantedAuthority> authorities =
                new ConfigurableRolesClaimConverter("realm_access.roles").convert(jwt);

        assertThat(authorities).isEmpty();
    }

    @Test
    void aMissingNestedSegmentYieldsNoAuthoritiesRatherThanThrowing() {
        Jwt jwt = jwtWithClaims(Map.of("realm_access", Map.of("other", "value")));

        Collection<GrantedAuthority> authorities =
                new ConfigurableRolesClaimConverter("realm_access.roles").convert(jwt);

        assertThat(authorities).isEmpty();
    }

    @Test
    void aClaimPathThatDoesNotResolveToAListYieldsNoAuthorities() {
        Jwt jwt = jwtWithClaims(Map.of("realm_access", "not-a-map"));

        Collection<GrantedAuthority> authorities =
                new ConfigurableRolesClaimConverter("realm_access.roles").convert(jwt);

        assertThat(authorities).isEmpty();
    }

    private static Jwt jwtWithClaims(Map<String, Object> extraClaims) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("sub", "user-1");
        extraClaims.forEach(builder::claim);
        return builder.build();
    }
}
