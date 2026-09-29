package com.revolutclone.analytics;

import com.revolutclone.analytics.categorisation.McCategory;
import com.revolutclone.analytics.categorisation.McCategoryRepository;
import com.revolutclone.analytics.categorisation.MerchantCategoryOverride;
import com.revolutclone.analytics.categorisation.MerchantCategoryOverrideRepository;
import com.revolutclone.analytics.categorisation.MerchantRule;
import com.revolutclone.analytics.categorisation.MerchantRuleRepository;
import com.revolutclone.analytics.categorisation.TransactionCategory;
import com.revolutclone.analytics.categorisation.TransactionCategoryRepository;
import com.revolutclone.analytics.readmodel.CardAuthorizationFact;
import com.revolutclone.analytics.readmodel.CardAuthorizationFactRepository;
import com.revolutclone.analytics.readmodel.LedgerEntryFact;
import com.revolutclone.analytics.readmodel.LedgerEntryFactRepository;
import com.revolutclone.analytics.spend.MonthlySpend;
import com.revolutclone.analytics.spend.MonthlySpendRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Both persistence annotations are required here, not optional: common-messaging's own
 * {@code MessagingAutoConfiguration} declares its own {@code @EntityScan} and
 * {@code @EnableJpaRepositories} scoped to its outbox/idempotent packages, and once either
 * appears anywhere in the context Boot's own auto-detection backs off for the whole context -
 * see docs/spring-boot-4-notes.md, and the identical pair on every other service here.
 *
 * <p>This is the project's first pure read model (plans/phase-06-vaults-analytics.md): no
 * outbox is ever appended here, and no synchronous call is ever made to another service. Its
 * whole job is to consume {@code ledger.entry.v1} and {@code card.authorization.v1} and
 * project facts - see {@link com.revolutclone.analytics.consumer}.
 */
@SpringBootApplication
@EntityScan(basePackageClasses = {LedgerEntryFact.class, CardAuthorizationFact.class, McCategory.class,
        MerchantRule.class, MerchantCategoryOverride.class, TransactionCategory.class, MonthlySpend.class})
@EnableJpaRepositories(basePackageClasses = {LedgerEntryFactRepository.class, CardAuthorizationFactRepository.class,
        McCategoryRepository.class, MerchantRuleRepository.class, MerchantCategoryOverrideRepository.class,
        TransactionCategoryRepository.class, MonthlySpendRepository.class})
public class AnalyticsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}
