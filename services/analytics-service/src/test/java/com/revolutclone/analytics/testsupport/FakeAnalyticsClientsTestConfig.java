package com.revolutclone.analytics.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Substitutes {@link FakeVaultAccountOpener} and {@link FakePaymentInitiationClient} for the real
 * {@code RestClient}-backed adapters in a full application context - same shape as card-service's
 * {@code FakeLedgerTestConfig}.
 *
 * <p>{@link Primary} rather than a bean definition override: the real adapters stay in the
 * context and stay constructible, so a test still fails if their {@code RestClient.Builder}
 * dependency ever goes missing.
 */
@TestConfiguration
public class FakeAnalyticsClientsTestConfig {

    @Bean
    @Primary
    public FakeVaultAccountOpener fakeVaultAccountOpener() {
        return new FakeVaultAccountOpener();
    }

    @Bean
    @Primary
    public FakePaymentInitiationClient fakePaymentInitiationClient() {
        return new FakePaymentInitiationClient();
    }
}
