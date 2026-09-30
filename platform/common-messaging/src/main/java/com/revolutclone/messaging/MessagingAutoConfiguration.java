package com.revolutclone.messaging;

import com.revolutclone.messaging.idempotent.IdempotentHandlerFactory;
import com.revolutclone.messaging.idempotent.ProcessedEvent;
import com.revolutclone.messaging.idempotent.ProcessedEventRepository;
import com.revolutclone.messaging.outbox.OutboxAppenderImpl;
import com.revolutclone.messaging.outbox.OutboxEvent;
import com.revolutclone.messaging.outbox.OutboxEventRepository;
import com.revolutclone.messaging.outbox.OutboxPublisher;
import com.revolutclone.messaging.spring.KafkaEventPublisher;
import com.revolutclone.messaging.spring.RetryTopicProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
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

    @Bean
    public Clock messagingClock() {
        return Clock.systemUTC();
    }

    @Bean
    public JsonMapper messagingJsonMapper() {
        return JsonMapper.builder().findAndAddModules().build();
    }
}
