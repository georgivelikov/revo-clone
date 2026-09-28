package com.revolutclone.simulator.card;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code baseUrl} names card-service, whose {@code /internal/cards/{cardId}/pan-for-network}
 * (task 05.8) is this service's one synchronous hop - everything else in the card flow travels as
 * an event. No default: CLAUDE.md's config-hygiene rule requires every endpoint from an environment
 * variable, with local defaults living only in {@code application-local.yml}.
 */
@ConfigurationProperties(prefix = "revolut.simulator.card")
public record CardSimulatorProperties(String baseUrl) {
}
