package com.revolutclone.ledger;

import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Both {@code @EntityScan}/{@code @EnableJpaRepositories} are required, not optional:
 * common-messaging's own {@code MessagingAutoConfiguration} declares its own scoped to its own
 * outbox/idempotent packages. Once either annotation appears anywhere in the context, Boot's
 * default auto-detection backs off entirely for the whole context, not just common-messaging's
 * packages - see docs/spring-boot-4-notes.md and identity-service's/account-service's identical
 * situation.
 *
 * <p>{@code @EnableRetry} activates {@code PostingEngine.postEntry}'s {@code @Retryable}
 * optimistic-lock retry wrapper (task 02.5) - without it the annotation is inert and the method
 * runs exactly like a plain call.
 */
@SpringBootApplication
@EntityScan(basePackageClasses = LedgerAccount.class)
@EnableJpaRepositories(basePackageClasses = LedgerAccountRepository.class)
@EnableRetry
public class LedgerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerServiceApplication.class, args);
    }
}
