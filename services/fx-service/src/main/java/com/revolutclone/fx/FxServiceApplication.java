package com.revolutclone.fx;

import com.revolutclone.fx.ledger.LedgerClientProperties;
import com.revolutclone.fx.quote.FxQuoteProperties;
import com.revolutclone.fx.readmodel.AccountOwner;
import com.revolutclone.fx.readmodel.AccountOwnerRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Both persistence annotations are required here, not optional: common-messaging's own
 * {@code MessagingAutoConfiguration} declares its own {@code @EntityScan} and
 * {@code @EnableJpaRepositories} scoped to its outbox/idempotent packages, and once either appears
 * anywhere in the context Boot's default auto-detection backs off for the whole context - see
 * docs/spring-boot-4-notes.md and the identical situation in every other service here.
 *
 * <p>The entities they cover are the two projections behind quoting ({@code account_owner},
 * {@code ledger_account_ref}). {@code fx_quote} is deliberately not among them: it is written through
 * {@code JdbcClient} because its {@code numeric(20,10)} rate columns cannot be mapped by an entity
 * without putting a decimal type where CLAUDE.md's hard rule 1 forbids one - see
 * {@code FxQuoteRepository}.
 */
@SpringBootApplication
@EntityScan(basePackageClasses = AccountOwner.class)
@EnableJpaRepositories(basePackageClasses = AccountOwnerRepository.class)
@EnableConfigurationProperties({FxQuoteProperties.class, LedgerClientProperties.class})
public class FxServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FxServiceApplication.class, args);
    }
}
