package com.revolutclone.analytics.categorisation;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * plans/phase-06-vaults-analytics.md's "Categorisation" section, verbatim: three layers, first
 * match wins.
 *
 * <pre>
 * 1. User override  - merchant_name -> category, set by the user. Highest priority.
 * 2. Merchant rules  - a seeded table of name patterns (TESCO* -> Groceries).
 * 3. MCC mapping     - the fallback, from the ISO MCC on the authorization.
 * </pre>
 *
 * Falls through to {@link Category#OTHER} / {@link CategorisationSource#DEFAULT} when nothing
 * matches at all - a merchant name that is {@code null} or matches no override/rule, and an MCC
 * that is {@code null} or unmapped.
 */
@Component
public class CategorisationResolver {

    private final MerchantCategoryOverrideRepository overrides;
    private final MerchantRuleRepository merchantRules;
    private final McCategoryRepository mccCategories;

    public CategorisationResolver(MerchantCategoryOverrideRepository overrides, MerchantRuleRepository merchantRules,
            McCategoryRepository mccCategories) {
        this.overrides = overrides;
        this.merchantRules = merchantRules;
        this.mccCategories = mccCategories;
    }

    @Transactional(readOnly = true)
    public CategorisationOutcome resolve(UUID userId, String merchantName, String mcc) {
        if (merchantName != null) {
            Optional<CategorisationOutcome> userOverride = overrides
                    .findByUserIdAndMerchantNameIgnoreCase(userId, merchantName)
                    .map(override -> new CategorisationOutcome(override.getCategory(), CategorisationSource.USER));
            if (userOverride.isPresent()) {
                return userOverride.get();
            }

            for (MerchantRule rule : merchantRules.findAllByOrderByPriorityAsc()) {
                if (matches(rule.getPattern(), merchantName)) {
                    return new CategorisationOutcome(rule.getCategory(), CategorisationSource.MERCHANT_RULE);
                }
            }
        }

        if (mcc != null) {
            Optional<CategorisationOutcome> mccMatch = mccCategories.findById(mcc)
                    .map(match -> new CategorisationOutcome(match.getCategory(), CategorisationSource.MCC));
            if (mccMatch.isPresent()) {
                return mccMatch.get();
            }
        }

        return new CategorisationOutcome(Category.OTHER, CategorisationSource.DEFAULT);
    }

    /**
     * SQL {@code LIKE} syntax ({@code %} any run of characters, {@code _} any single character),
     * matched case-insensitively - the resolver runs in application code, not as a database
     * query, since the seeded rule set is small enough to hold in memory per resolution and this
     * keeps the whole three-layer decision in one place to unit-test.
     */
    private static boolean matches(String likePattern, String merchantName) {
        StringBuilder regex = new StringBuilder("(?i)");
        for (int i = 0; i < likePattern.length(); i++) {
            char c = likePattern.charAt(i);
            switch (c) {
                case '%' -> regex.append(".*");
                case '_' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return merchantName.matches(regex.toString());
    }
}
