package com.revolutclone.analytics.vault;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where account-service lives. Bound from {@code revolut.analytics.account.*}, whose value in
 * {@code application.yml} is an environment variable and nothing else - CLAUDE.md rule 9; the
 * local-dev default lives in {@code application-local.yml}.
 */
@ConfigurationProperties(prefix = "revolut.analytics.account")
public record AccountClientProperties(String baseUrl) {
}
