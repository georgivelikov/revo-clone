package com.revolutclone.card;

import com.revolutclone.card.authz.CardAuthorizationProperties;
import com.revolutclone.card.authz.CardLimitProperties;
import com.revolutclone.card.crypto.CardCryptoProperties;
import com.revolutclone.card.domain.Card;
import com.revolutclone.card.domain.CardRepository;
import com.revolutclone.card.ledger.LedgerClientProperties;
import com.revolutclone.card.readmodel.FundingAccount;
import com.revolutclone.card.readmodel.FundingAccountRepository;
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
@EntityScan(basePackageClasses = {Card.class, FundingAccount.class})
@EnableJpaRepositories(basePackageClasses = {CardRepository.class, FundingAccountRepository.class})
@EnableConfigurationProperties({
        CardCryptoProperties.class,
        CardAuthorizationProperties.class,
        CardLimitProperties.class,
        LedgerClientProperties.class})
public class CardServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CardServiceApplication.class, args);
    }
}
