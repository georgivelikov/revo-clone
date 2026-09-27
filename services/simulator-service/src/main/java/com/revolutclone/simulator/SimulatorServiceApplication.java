package com.revolutclone.simulator;

import com.revolutclone.messaging.api.EventPublisher;
import com.revolutclone.messaging.spring.KafkaEventPublisher;
import com.revolutclone.simulator.fx.FxSimulatorProperties;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code common-messaging}'s own {@code MessagingAutoConfiguration} is excluded here: it
 * {@code @EntityScan}s and {@code @EnableJpaRepositories} the outbox/idempotent-consumer tables,
 * which need a real {@code DataSource} - this service owns no database (see
 * docs/architecture.md), and its pom excludes the JPA/Flyway/Postgres jars that configuration
 * would otherwise need. {@code excludeName} (a class-name string), not {@code exclude} (a
 * {@code Class<?>} literal), because referencing the class by literal forces the JVM to load and
 * verify it - including its JPA-repository {@code basePackageClasses} annotation attributes -
 * which throws {@code NoClassDefFoundError} once those jars are gone. See
 * {@code services/api-gateway}'s {@code GatewayApplication} for the same pattern.
 *
 * <p>What this service DOES want from that module - {@link EventPublisher}/
 * {@link KafkaEventPublisher}, the one implementation of {@code EventPublisher} in the project -
 * is wired by hand below instead, since the excluded auto-configuration is what would otherwise
 * have component-scanned it into the context.
 */
@SpringBootApplication(excludeName = "com.revolutclone.messaging.MessagingAutoConfiguration")
@EnableScheduling
@EnableConfigurationProperties(FxSimulatorProperties.class)
public class SimulatorServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorServiceApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JsonMapper jsonMapper() {
        return JsonMapper.builder().findAndAddModules().build();
    }

    @Bean
    EventPublisher eventPublisher(KafkaTemplate<Object, Object> kafkaTemplate) {
        return new KafkaEventPublisher(kafkaTemplate);
    }
}
