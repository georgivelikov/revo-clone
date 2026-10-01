package com.revolutclone.messaging.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code outbox.pending.count} and {@code outbox.oldest.age.seconds}
 * (plans/phase-08-load-and-observability.md task 3) - both backed by the repository and pulled
 * at scrape time via {@link Gauge}'s weak-referenced value-supplier, not pushed on a schedule.
 * Age, not just count, is what the phase's own Instrumentation section says to alert on: "a
 * growing count might just be throughput, but an old row means the poller is genuinely stuck."
 *
 * <p>Both suppliers return {@link Number} ({@code long}), not {@code double} - CLAUDE.md hard
 * rule 1 bans {@code double}/{@code float} outright and agent/tripwire.ps1 enforces it by
 * pattern, with no carve-out for a non-money {@code double} such as a gauge value. Same
 * {@code Gauge.builder(name, Supplier<Number>)} overload {@code DltDepthMetrics} already uses
 * for the identical reason.
 *
 * <p>{@code MeterRegistry} arrives as an {@link ObjectProvider}, not a hard constructor
 * dependency - same reason as {@code DltDepthMetrics} (see its javadoc): this module's own test
 * context ({@code TestMessagingApplication}) carries no {@code spring-boot-starter-actuator}, so
 * no {@code MeterRegistry} bean exists there, and a hard dependency would fail every
 * {@code @SpringBootTest} in this module at context-creation time rather than simply registering
 * no gauges. Every real consumer of this module has actuator, so in production this always
 * resolves.
 */
public class OutboxMetrics {

    // Micrometer gauges hold only a weak reference to their supplier by default (see
    // docs/spring-boot-4-notes.md) - without this field keeping both suppliers reachable for
    // this component's own lifetime, the next GC can collect one and its gauge silently stops
    // updating instead of erroring.
    private final List<Supplier<Number>> gaugeState;

    public OutboxMetrics(OutboxEventRepository repository, Clock clock, ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        if (meterRegistry == null) {
            this.gaugeState = List.of();
            return;
        }
        Supplier<Number> pendingCount = repository::countByPublishedAtIsNull;
        Supplier<Number> oldestAgeSeconds = () -> ageOf(repository, clock);
        this.gaugeState = List.of(pendingCount, oldestAgeSeconds);

        Gauge.builder("outbox.pending.count", pendingCount)
                .description("Unpublished rows in this service's own outbox table")
                .register(meterRegistry);
        Gauge.builder("outbox.oldest.age.seconds", oldestAgeSeconds)
                .description("Age of the oldest unpublished outbox row, zero when nothing is pending")
                .register(meterRegistry);
    }

    private static long ageOf(OutboxEventRepository repository, Clock clock) {
        return repository.oldestUnpublishedCreatedAt()
                .map(oldest -> Duration.between(oldest, clock.instant()).getSeconds())
                .orElse(0L);
    }
}
