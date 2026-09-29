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
 *
 * <p><strong>Documented gap: does not re-trigger budget threshold detection.</strong> Moving a
 * settlement's spend from one category's bucket to another can, in principle, push either bucket
 * across an 80%/100% budget line without task 06.5's
 * {@link com.revolutclone.analytics.budget.BudgetThresholdService} ever being asked to look - it is
 * only invoked from {@link com.revolutclone.analytics.consumer.MonthlySpendProjectionHandler}'s own
 * increment, not from {@link #decrement}/{@code increment} here. Task 06.5's own scope note names
 * this trade-off explicitly and leaves it as a deliberate, honest gap rather than a guessed-at
 * half-implementation: neither {@code BudgetAlertTest} nor {@code ReplayDoesNotRealertTest} - the
 * two tests that task is actually on the hook for - exercise the retro-apply path, and wiring it in
 * here would need its own decision about what timestamp to stamp {@code alerted_at_*} with (this
 * method has no settlement event of its own to borrow an {@code occurredAt} from - only
 * {@link com.revolutclone.analytics.readmodel.CardAuthorizationFact#getSettledAt()}, which is a
 * projection field, not a live event) and its own test coverage, neither of which this task's scope
 * includes. A later task can add it once retro-apply itself needs revisiting for its documented
 * "O(transactions for that merchant), should be async" gap (the phase's "Risks & gotchas" section).
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
