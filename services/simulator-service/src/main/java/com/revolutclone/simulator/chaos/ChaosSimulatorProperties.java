package com.revolutclone.simulator.chaos;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the services this control plane forwards levers to actually live. No defaults:
 * CLAUDE.md's config-hygiene rule requires every endpoint from an environment variable, with
 * local defaults living only in {@code application-local.yml}.
 *
 * <p>{@code outbox.pause}/{@code outbox.resume} only ever target {@code payment-service} or
 * {@code ledger-service} - the two services the failure experiments in
 * plans/phase-07-resilience-and-scale.md actually exercise the outbox of - rather than a generic
 * name-to-URL map for every service on the bus.
 */
@ConfigurationProperties(prefix = "revolut.simulator.chaos")
public record ChaosSimulatorProperties(String ledgerBaseUrl, String fxBaseUrl, String paymentBaseUrl) {
}
