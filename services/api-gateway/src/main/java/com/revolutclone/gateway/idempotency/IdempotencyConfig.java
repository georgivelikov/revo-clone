package com.revolutclone.gateway.idempotency;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link IdempotencyProperties}. The filter itself is a {@code @Component}; this exists only
 * because this service does not use {@code @ConfigurationPropertiesScan} - {@code common-security}'s
 * properties are enabled the same way, on the gateway's security config.
 */
@Configuration
@EnableConfigurationProperties(IdempotencyProperties.class)
class IdempotencyConfig {
}
