package com.revolutclone.analytics.budget;

import com.revolutclone.analytics.categorisation.Category;
import com.revolutclone.analytics.categorisation.InvalidCategoryException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sets (or replaces) a user's spending ceiling for one {@code (category, month, currency)} bucket
 * - point 1 of task 06.5's spec: "a way to set/create a budget... upsert semantics given the
 * table's unique constraint." Setting a budget that already exists corrects its {@code
 * limit_minor} only; it deliberately never touches {@code alerted_at_80}/{@code alerted_at_100} -
 * lowering or raising a limit after an alert has already fired does not retroactively un-notify
 * the user, and no test asks for that behaviour either way.
 *
 * <p>No UI calls this yet and no test pins its exact request/response shape, per the task's own
 * scope note - {@code BudgetAlertTest}/{@code ReplayDoesNotRealertTest} construct {@link Budget}
 * rows through this service exactly as a real caller would, but assert on {@link BudgetRepository}
 * and the outbox, not on this service's return shape.
 */
@Component
public class BudgetService {

    private final BudgetRepository budgets;

    public BudgetService(BudgetRepository budgets) {
        this.budgets = budgets;
    }

    @Transactional
    public Budget upsert(UUID userId, String category, LocalDate month, long limitMinor, String currency) {
        if (!Category.ALL.contains(category)) {
            throw new InvalidCategoryException(category);
        }
        LocalDate firstOfMonth = month.withDayOfMonth(1);
        Budget budget = budgets.findByUserIdAndCategoryAndMonthAndCurrency(userId, category, firstOfMonth, currency)
                .orElseGet(() -> new Budget(UUID.randomUUID(), userId, category, firstOfMonth, limitMinor, currency));
        budget.setLimitMinor(limitMinor);
        return budgets.save(budget);
    }

    @Transactional(readOnly = true)
    public List<Budget> list(UUID userId) {
        return budgets.findByUserId(userId);
    }
}
