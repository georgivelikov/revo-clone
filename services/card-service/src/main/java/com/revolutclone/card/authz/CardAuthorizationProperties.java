package com.revolutclone.card.authz;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long an approved authorization's hold stands before the ledger releases it on its own.
 *
 * <p>plans/phase-05-cards.md fixes this at seven days ("ledger: HOLD billing amount, 7-day expiry",
 * and "Expiry (7 days, no settlement) - sweep releases the hold"), which is the default in
 * {@code application.yml}. It is a property rather than a constant because it is a policy: the
 * simulated clock task 05.8 introduces exists precisely so that seven days can be reached without
 * waiting seven days, and a test of the expiry path should be able to state the window it is
 * crossing.
 */
@ConfigurationProperties(prefix = "revolut.card.authorization")
public record CardAuthorizationProperties(Duration holdTtl) {
}
