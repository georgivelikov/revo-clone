package com.revolutclone.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * No {@code @EntityScan}/{@code @EnableJpaRepositories} override yet - this task (02.1) is
 * scaffold + schema only, no domain entities exist. Task 02.3 will need to add both, matching
 * identity-service's and account-service's precedent: once common-messaging's own
 * {@code MessagingAutoConfiguration} declares scoped {@code @EntityScan}/
 * {@code @EnableJpaRepositories}, Boot's default auto-detection backs off for the whole
 * context, so this service's own entities and repositories become invisible to Spring Data
 * without an explicit override naming them. See docs/spring-boot-4-notes.md.
 */
@SpringBootApplication
public class LedgerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerServiceApplication.class, args);
    }
}
