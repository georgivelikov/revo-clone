package com.revolutclone.security.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Admin-client connection details for {@link KeycloakIdentityAdapter}. */
@ConfigurationProperties(prefix = "revolut.identity.keycloak")
public record KeycloakProperties(String serverUrl, String realm, String adminClientId, String adminClientSecret) {
}
