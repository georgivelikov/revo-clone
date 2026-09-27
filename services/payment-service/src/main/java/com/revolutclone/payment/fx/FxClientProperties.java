package com.revolutclone.payment.fx;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where fx-service lives. Bound from {@code revolut.payment.fx.*}, whose value in
 * {@code application.yml} is an environment variable and nothing else - CLAUDE.md rule 9; the
 * local-dev default lives in {@code application-local.yml}.
 */
@ConfigurationProperties(prefix = "revolut.payment.fx")
public record FxClientProperties(String baseUrl) {
}
