package com.revolutclone.simulator.card;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Substitutes {@link FakeCardNetworkPanClient} for {@code CardNetworkPanRestClient} in a full
 * application context - same reasoning as payment-service's {@code FakeFxQuotesTestConfig}:
 * {@link Primary} rather than a bean-definition override, so the real adapter stays in the context
 * and stays constructible (a test still fails if its {@code RestClient.Builder} or
 * {@code CardSimulatorProperties} dependency ever goes missing), while nothing in a controller test
 * has to reach an actual card-service over the network.
 */
@TestConfiguration
public class FakeCardNetworkPanClientTestConfig {

    @Bean
    @Primary
    public FakeCardNetworkPanClient fakeCardNetworkPanClient() {
        return new FakeCardNetworkPanClient();
    }
}
