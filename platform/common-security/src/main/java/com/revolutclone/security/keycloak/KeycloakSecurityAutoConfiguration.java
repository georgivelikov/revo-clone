package com.revolutclone.security.keycloak;

import com.revolutclone.security.SecurityAutoConfiguration;
import com.revolutclone.security.api.IdentityProviderPort;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Selects {@link KeycloakIdentityAdapter} as the {@link IdentityProviderPort} implementation
 * when {@code revolut.identity.provider=keycloak}. Ordered before
 * {@link SecurityAutoConfiguration} so its {@code @ConditionalOnMissingBean} fallback to
 * {@code InMemoryIdentityAdapter} only fires when this configuration did not activate.
 */
@AutoConfiguration
@AutoConfigureBefore(SecurityAutoConfiguration.class)
@EnableConfigurationProperties(KeycloakProperties.class)
@ConditionalOnProperty(prefix = "revolut.identity", name = "provider", havingValue = "keycloak")
public class KeycloakSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Keycloak keycloakAdminClient(KeycloakProperties properties) {
        return KeycloakBuilder.builder()
                .serverUrl(properties.serverUrl())
                .realm(properties.realm())
                .clientId(properties.adminClientId())
                .clientSecret(properties.adminClientSecret())
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(IdentityProviderPort.class)
    public IdentityProviderPort keycloakIdentityAdapter(Keycloak keycloakAdminClient, KeycloakProperties properties) {
        return new KeycloakIdentityAdapter(keycloakAdminClient, properties.realm());
    }
}
