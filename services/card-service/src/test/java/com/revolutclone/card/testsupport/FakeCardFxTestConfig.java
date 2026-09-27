package com.revolutclone.card.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Substitutes {@link FakeCardFxRates} for {@code CardFxRatesRestClient} in a full application context.
 *
 * <p>{@link Primary} rather than a bean definition override, for the same reason
 * {@link FakeLedgerTestConfig} does it: the real adapter stays in the context and stays constructible,
 * so a test still fails if its {@code RestClient.Builder} dependency ever goes missing - which, given
 * Boot 4.1 moved that autoconfiguration into its own module, is a failure worth keeping reachable.
 */
@TestConfiguration
public class FakeCardFxTestConfig {

    @Bean
    @Primary
    public FakeCardFxRates fakeCardFxRates() {
        return new FakeCardFxRates();
    }
}
