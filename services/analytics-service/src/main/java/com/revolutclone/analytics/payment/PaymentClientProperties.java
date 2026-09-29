package com.revolutclone.analytics.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where payment-service lives. Bound from {@code revolut.analytics.payment.*}, whose value in
 * {@code application.yml} is an environment variable and nothing else - CLAUDE.md rule 9; the
 * local-dev default lives in {@code application-local.yml}.
 */
@ConfigurationProperties(prefix = "revolut.analytics.payment")
public record PaymentClientProperties(String baseUrl) {
}
