package com.revolutclone.messaging;

import com.revolutclone.messaging.idempotent.IdempotentHandlerFactory;
import com.revolutclone.messaging.idempotent.ProcessedEvent;
import com.revolutclone.messaging.idempotent.ProcessedEventRepository;
import com.revolutclone.messaging.outbox.OutboxAppenderImpl;
import com.revolutclone.messaging.outbox.OutboxEvent;
import com.revolutclone.messaging.outbox.OutboxEventRepository;
import com.revolutclone.messaging.outbox.OutboxMetrics;
import com.revolutclone.messaging.outbox.OutboxPublisher;
import com.revolutclone.messaging.spring.KafkaEventPublisher;
import com.revolutclone.messaging.spring.RetryTopicProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the outbox, the idempotent consumer and (via component scan) the {@code spring/}
 * adapter into any service that has this module on its classpath, so including it is enough -
 * no per-service entity-scan or repository config needed.
 */
@AutoConfiguration
@EnableScheduling
@EntityScan(basePackageClasses = {OutboxEvent.class, ProcessedEvent.class})
@EnableJpaRepositories(basePackageClasses = {OutboxEventRepository.class, ProcessedEventRepository.class})
@EnableConfigurationProperties(RetryTopicProperties.class)
@ComponentScan(
        basePackageClasses = {
            OutboxAppenderImpl.class, OutboxPublisher.class, IdempotentHandlerFactory.class, KafkaEventPublisher.class
        })
public class MessagingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MessagingAutoConfiguration.class);

    @Bean
    public Clock messagingClock() {
        return Clock.systemUTC();
    }

    @Bean
    public JsonMapper messagingJsonMapper() {
        return JsonMapper.builder().findAndAddModules().build();
    }

    @Bean
    public OutboxMetrics outboxMetrics(OutboxEventRepository repository, Clock clock,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new OutboxMetrics(repository, clock, meterRegistryProvider);
    }

    /**
     * plans/phase-07-resilience-and-scale.md's Risks &amp; gotchas: "Profile-gate it and add a
     * startup log line when chaos is active, so you never spend an hour debugging a fault you
     * injected last week." One runner here covers every service that carries this module
     * (payment, ledger, fx, ...); {@code simulator-service} excludes this auto-configuration
     * entirely (see its {@code SimulatorServiceApplication}) and logs its own equivalent line.
     */
    @Bean
    @Profile("chaos")
    public ApplicationRunner chaosProfileStartupLogger() {
        return (ApplicationArguments args) -> log.warn(
                "CHAOS PROFILE ACTIVE - fault-injection endpoints under /admin/chaos/** are enabled on this instance");
    }
}
