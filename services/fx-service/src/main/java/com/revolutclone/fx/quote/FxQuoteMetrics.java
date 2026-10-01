package com.revolutclone.fx.quote;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * {@code fx.quote.active.count} (plans/phase-08-load-and-observability.md task 3) - how many
 * quotes currently hold a reservation and a firm rate, i.e. money this service has promised a
 * client but not yet settled one way or the other. Pulled from {@link FxQuoteRepository} at
 * scrape time via {@link Gauge}'s value-supplier, same pattern as {@code OutboxMetrics}.
 */
@Component
public class FxQuoteMetrics {

    public FxQuoteMetrics(FxQuoteRepository repository, MeterRegistry meterRegistry) {
        Gauge.builder("fx.quote.active.count", repository, FxQuoteRepository::countActive)
                .description("Quotes currently ACTIVE - reservation held, rate firm, not yet executed or expired")
                .register(meterRegistry);
    }
}
