package com.revolutclone.analytics.categorisation;

import com.revolutclone.analytics.readmodel.CardAuthorizationFact;
import com.revolutclone.analytics.readmodel.CardAuthorizationFactRepository;
import com.revolutclone.analytics.spend.MonthlySpendRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * plans/phase-06-vaults-analytics.md's "Categorisation" section, verbatim: "A user override must
 * retro-apply to that merchant's past transactions and update the affected monthly aggregates."
 * Called by {@link MerchantCategoryOverrideService#setOverride} once the override row itself has
 * been written, so every past authorization for that merchant that disagrees with the new
 * category gets corrected in the same transaction as the override.
 *
 * <p><strong>Synchronous, deliberately.</strong> The phase's own "Risks & gotchas" section
 * suggests doing this asynchronously via an event for a large history, with an "updating..."
 * affordance in the UI. There is no UI to show that yet (the Vaults/Analytics screens are tasks
 * 06.9/06.10) and no test here demands async behaviour. Building an event + consumer + UI polling
 * loop with nothing yet to exercise it would be speculative machinery ahead of an actual
 * performance problem, so this runs inline inside the override-setting transaction. A later task
 * can revisit this once there is a real "large history" to justify the complexity.
 *
 * <p><strong>Idempotent by construction.</strong> Setting the same override value twice finds
 * every past transaction for that merchant already at the target category, and
 * {@link #retroApply} skips a row whose category already equals the new value - so a repeat call
 * touches nothing, neither {@code transaction_category} nor {@code monthly_spend}.
 */
@Component
public class RetroApplyService {

    private final CardAuthorizationFactRepository facts;
    private final TransactionCategoryRepository categories;
    private final MonthlySpendRepository monthlySpend;

    public RetroApplyService(CardAuthorizationFactRepository facts, TransactionCategoryRepository categories,
            MonthlySpendRepository monthlySpend) {
        this.facts = facts;
        this.categories = categories;
        this.monthlySpend = monthlySpend;
    }

    /**
     * Finds every authorization this user has ever had with {@code merchantName} - regardless of
     * lifecycle state, since even a still-pending one has a category worth correcting - and, for
     * each whose current category differs from {@code newCategory}, updates
     * {@code transaction_category} and (only for one that has actually settled) corrects the
     * {@code monthly_spend} buckets it contributed to.
     */
    @Transactional
    public void retroApply(UUID userId, String merchantName, String newCategory) {
        List<CardAuthorizationFact> merchantAuthorizations = facts.findByUserIdAndMerchantNameIgnoreCase(userId,
                merchantName);
        for (CardAuthorizationFact fact : merchantAuthorizations) {
            categories.findById(fact.getAuthorizationId()).ifPresent(transactionCategory -> {
                String oldCategory = transactionCategory.getCategory();
                if (oldCategory.equals(newCategory)) {
                    // Point 4 of the task's own spec: already correct, so nothing to touch - not
                    // even a redundant write of the same value.
                    return;
                }

                transactionCategory.retroApply(newCategory);
                categories.save(transactionCategory);

                Long settledMinor = fact.getSettledMinor();
                if (settledMinor == null) {
                    // Never settled (still APPROVED, or REVERSED/EXPIRED before settling): no
                    // monthly_spend bucket was ever built from it, so there is nothing to correct.
                    return;
                }

                // Same bucketing/fields MonthlySpendProjectionHandler used to build the bucket in
                // the first place: month off settlement event time, wallet-currency amount.
                LocalDate month = fact.getSettledAt().atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
                String currency = fact.getBillingCurrency();
                monthlySpend.decrement(userId, month, oldCategory, currency, settledMinor);
                monthlySpend.increment(userId, month, newCategory, currency, settledMinor);
            });
        }
    }
}
