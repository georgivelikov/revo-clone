package com.revolutclone.ledger;

import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Both annotations are required, not optional: common-messaging's own
 * {@code MessagingAutoConfiguration} declares its own {@code @EntityScan} and
 * {@code @EnableJpaRepositories}, scoped to its own outbox/idempotent packages. Once either
 * annotation appears anywhere in the context, Boot's default auto-detection backs off entirely
 * for the whole context, not just common-messaging's packages - see
 * docs/spring-boot-4-notes.md and identity-service's/account-service's identical situation.
 */
@SpringBootApplication
@EntityScan(basePackageClasses = LedgerAccount.class)
@EnableJpaRepositories(basePackageClasses = LedgerAccountRepository.class)
public class LedgerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerServiceApplication.class, args);
    }
}
