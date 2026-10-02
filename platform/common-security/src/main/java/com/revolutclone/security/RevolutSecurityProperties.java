package com.revolutclone.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Binds {@code revolut.security.roles-claim} for {@link com.revolutclone.security.api.ConfigurableRolesClaimConverter}. */
@ConfigurationProperties(prefix = "revolut.security")
public record RevolutSecurityProperties(
        @DefaultValue("realm_access.roles") String rolesClaim,
        @NestedConfigurationProperty ServiceAccount serviceAccount) {

    public RevolutSecurityProperties {
        if (serviceAccount == null) {
            serviceAccount = new ServiceAccount(null, null, null);
        }
    }

    /**
     * {@code revolut.security.service-account.{token-uri,client-id,client-secret}} - a
     * client-credentials identity for the one case a live user JWT cannot cover: a
     * synchronous outbound call made from a Kafka consumer thread, where there is no request
     * and so no caller token to relay. See {@link ServiceAccountTokenProvider}'s own javadoc
     * for why this exists and what it is not for.
     */
    public record ServiceAccount(String tokenUri, String clientId, String clientSecret) {
    }
}
