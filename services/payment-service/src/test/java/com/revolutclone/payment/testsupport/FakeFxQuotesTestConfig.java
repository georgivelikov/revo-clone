package com.revolutclone.payment.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Substitutes {@link FakeFxQuotes} for {@code FxQuoteRestClient} in a full application context.
 *
 * <p>{@link Primary} rather than a bean definition override: the real adapter stays in the
 * context and stays constructible, so a test still fails if its {@code RestClient.Builder} or
 * {@code CircuitBreakerFactory} dependency ever goes missing.
 */
@TestConfiguration
public class FakeFxQuotesTestConfig {

    @Bean
    @Primary
    public FakeFxQuotes fakeFxQuotes() {
        return new FakeFxQuotes();
    }
}
