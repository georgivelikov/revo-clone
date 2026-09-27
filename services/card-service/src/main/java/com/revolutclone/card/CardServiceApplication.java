package com.revolutclone.card;

import com.revolutclone.card.crypto.CardCryptoProperties;
import com.revolutclone.card.domain.Card;
import com.revolutclone.card.domain.CardRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Both persistence annotations are required here, not optional: common-messaging's own
 * {@code MessagingAutoConfiguration} declares its own {@code @EntityScan} and
 * {@code @EnableJpaRepositories} scoped to its outbox/idempotent packages, and once either
 * appears anywhere in the context Boot's default auto-detection backs off for the whole
 * context - see docs/spring-boot-4-notes.md and every other service here.
 */
@SpringBootApplication
@EntityScan(basePackageClasses = Card.class)
@EnableJpaRepositories(basePackageClasses = CardRepository.class)
@EnableConfigurationProperties(CardCryptoProperties.class)
public class CardServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CardServiceApplication.class, args);
    }
}
