package com.revolutclone.notification;

import com.revolutclone.notification.config.NotificationProperties;
import com.revolutclone.notification.domain.Notification;
import com.revolutclone.notification.domain.NotificationRepository;
import com.revolutclone.notification.readmodel.AccountOwner;
import com.revolutclone.notification.readmodel.AccountOwnerRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Both scan annotations are required here, not optional: common-messaging's
 * {@code MessagingAutoConfiguration} declares its own {@code @EntityScan} and
 * {@code @EnableJpaRepositories} for its outbox/idempotent packages, and once either annotation
 * appears anywhere in the context Boot's own auto-detection backs off for the whole context -
 * see docs/spring-boot-4-notes.md, and the same pair on every other service here.
 *
 * <p>STOMP sessions live in each instance's own JVM memory, so a user's socket can be on a
 * different replica than the consumer that received their event - this service now runs any
 * number of instances by routing every push through a Redis pub/sub fan-out instead of the local
 * broker directly. {@code WebSocketConfig} and {@code RedisFanOutRelay} carry the full reasoning.
 */
@SpringBootApplication
@EntityScan(basePackageClasses = {Notification.class, AccountOwner.class})
@EnableJpaRepositories(basePackageClasses = {NotificationRepository.class, AccountOwnerRepository.class})
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
