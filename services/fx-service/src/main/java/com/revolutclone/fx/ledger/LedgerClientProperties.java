package com.revolutclone.fx.ledger;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where ledger-service lives. Bound from {@code revolut.fx.ledger.*}, whose value in
 * {@code application.yml} is an environment variable and nothing else - CLAUDE.md rule 9; the
 * local-dev default lives in {@code application-local.yml}.
 */
@ConfigurationProperties(prefix = "revolut.fx.ledger")
public record LedgerClientProperties(String baseUrl) {
}
