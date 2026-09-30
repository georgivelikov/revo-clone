package com.revolutclone.messaging.spring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * "A Micrometer gauge per DLT so depth is visible in Phase 08's dashboard" -
 * plans/phase-07-resilience-and-scale.md §DLT tooling. The topic list comes from whichever main
 * topics this service already has a real {@code @KafkaListener} registered for, read off
 * {@link KafkaListenerEndpointRegistry} once the context is up - not from
 * {@link RetryTopicProperties}, which today no service actually configures and which only
 * governs the out-of-process retry relay ({@code RetryTopicRelay}). A DLT receives fatal dead
 * letters from {@link KafkaErrorHandlingConfig#kafkaErrorHandler} unconditionally, regardless of
 * that property, so tying gauge registration to it would leave every DLT's depth invisible.
 * Reading the listener registry instead means every consumer a service already has gets a depth
 * gauge with zero extra per-service configuration.
 *
 * <p>{@code MeterRegistry} is injected as an {@link ObjectProvider} rather than a hard
 * constructor dependency: this module's own test context ({@code TestMessagingApplication}) has
 * no {@code spring-boot-starter-actuator} on its classpath (only {@code micrometer-core}, needed
 * for the {@code Gauge}/{@code MeterRegistry} types to compile), so no {@code MeterRegistry}
 * bean exists there - a hard dependency would break every {@code @SpringBootTest} in this
 * module. Every real consumer of this module has actuator today, so in production this always
 * resolves.
 */
@Component
public class DltDepthMetrics {

    static final String METRIC_NAME = "kafka.dlt.depth";

    private final DltAdminService dltAdminService;
    private final KafkaListenerEndpointRegistry listenerRegistry;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    // Micrometer gauges hold only a weak reference to their state object by default (see
    // docs/spring-boot-4-notes.md) - without this field keeping each supplier reachable for the
    // component's own (singleton) lifetime, the next GC can collect it and the gauge silently
    // stops updating instead of erroring.
    private final List<Supplier<Number>> gaugeState = new ArrayList<>();

    public DltDepthMetrics(DltAdminService dltAdminService, KafkaListenerEndpointRegistry listenerRegistry,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.dltAdminService = dltAdminService;
        this.listenerRegistry = listenerRegistry;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    void registerGauges() {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        if (meterRegistry == null) {
            return;
        }
        for (String mainTopic : consumedMainTopics()) {
            Supplier<Number> depth = () -> dltAdminService.depth(mainTopic);
            gaugeState.add(depth);
            Gauge.builder(METRIC_NAME, depth)
                    .tag("topic", mainTopic)
                    .description("Current record count on " + mainTopic + RetryTopicNames.DLT_SUFFIX)
                    .register(meterRegistry);
        }
    }

    private List<String> consumedMainTopics() {
        return listenerRegistry.getListenerContainers().stream()
                .map(MessageListenerContainer::getContainerProperties)
                .map(ContainerProperties::getTopics)
                .filter(Objects::nonNull)
                .flatMap(Arrays::stream)
                // RetryTopicRelay's own containers are built by hand, never registered here, so
                // this filter is defensive rather than load-bearing - it only ever sees genuine
                // main-topic @KafkaListener containers today.
                .filter(topic -> RetryTopicNames.mainTopicOf(topic).equals(topic))
                .distinct()
                .sorted()
                .toList();
    }
}
