package com.revolutclone.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Binds {@code revolut.security.roles-claim} for {@link com.revolutclone.security.api.ConfigurableRolesClaimConverter}. */
@ConfigurationProperties(prefix = "revolut.security")
public record RevolutSecurityProperties(@DefaultValue("realm_access.roles") String rolesClaim) {
}
