package com.revolutclone.security.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reads the roles claim from a dotted path in configuration rather than hardcoding it.
 * {@code realm_access.roles} is a Keycloak invention with no basis in the OIDC spec, so it
 * does not belong baked into security config - see {@link RevolutSecurityProperties}, bound
 * from {@code revolut.security.roles-claim} (default {@code realm_access.roles}), which is
 * what {@link SecurityAutoConfiguration} constructs this converter from.
 *
 * <p>A missing claim at any segment of the path yields no authorities rather than throwing -
 * an absent claim is not malformed input, it just means this token carries no roles.
 */
public class ConfigurableRolesClaimConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private final List<String> claimPath;

    public ConfigurableRolesClaimConverter(String rolesClaim) {
        this.claimPath = List.of(rolesClaim.split("\\."));
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object current = jwt.getClaims();
        for (int i = 0; i < claimPath.size(); i++) {
            if (!(current instanceof Map<?, ?> map)) {
                return Set.of();
            }
            current = map.get(claimPath.get(i));
            if (current == null) {
                return Set.of();
            }
        }
        if (!(current instanceof Collection<?> roles)) {
            return Set.of();
        }
        return roles.stream()
                .map(Object::toString)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toUnmodifiableSet());
    }
}
