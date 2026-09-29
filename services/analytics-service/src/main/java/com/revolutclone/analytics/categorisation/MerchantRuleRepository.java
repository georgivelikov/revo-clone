package com.revolutclone.analytics.categorisation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRuleRepository extends JpaRepository<MerchantRule, String> {

    /**
     * All seeded rules, lowest {@code priority} first, so {@link CategorisationResolver} can
     * stop at the first pattern that matches without needing to compare priorities itself.
     */
    List<MerchantRule> findAllByOrderByPriorityAsc();
}
