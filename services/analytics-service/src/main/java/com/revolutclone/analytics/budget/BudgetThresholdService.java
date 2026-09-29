package com.revolutclone.analytics.budget;

import com.revolutclone.analytics.spend.MonthlySpend;
import com.revolutclone.analytics.spend.MonthlySpendRepository;
import com.revolutclone.contracts.events.NotificationPushV1;
import com.revolutclone.messaging.api.OutboxAppender;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * plans/phase-06-vaults-analytics.md's "Aggregates and budgets" section, verbatim: "On crossing
 * 80% or 100% [of {@code limit_minor}], emit to {@code notification.push.v1} - and use the
 * {@code alerted_at_*} columns as the idempotency guard so a replay does not re-notify the user for
 * six months of history."
 *
 * <p>Called by {@link com.revolutclone.analytics.consumer.MonthlySpendProjectionHandler} right
 * after its own {@code monthly_spend} increment, inside the same transaction and (via that class's
 * wrapping in {@link com.revolutclone.messaging.idempotent.IdempotentHandler}) the same {@code
 * processed_event} guard. That guard alone would already stop an ordinary Kafka redelivery from
 * getting here twice - but it is not the guard this class relies on, because a full projection
 * rebuild (drop the analytics DB, replay both topics from offset zero) wipes {@code
 * processed_event} along with {@code monthly_spend} while leaving {@code budget} - user-configured
 * data, not a projection - untouched. The {@code alerted_at_80}/{@code alerted_at_100} columns are
 * therefore checked and set here regardless of whatever outer dedup happens to be in effect: they
 * are the guard of last resort, and the only one still standing after a rebuild.
 *
 * <p><strong>Two independent thresholds.</strong> Crossing 80% and crossing 100% are checked and
 * guarded separately, so a single settlement that jumps a bucket straight past both fires both
 * alerts exactly once each in the same call - {@code BudgetAlertTest}'s "fires once each, never
 * twice."
 *
 * <p><strong>Integer-only threshold math.</strong> {@code totalMinor * 100 >= limitMinor * percent}
 * is exact (CLAUDE.md hard rule 1: no {@code double}/{@code float} in money comparisons) and safe
 * from overflow for every amount this domain can produce: both operands are minor-unit money
 * figures, bounded far below {@code Long.MAX_VALUE / 100} (~92 quadrillion), so the {@code * 100}
 * multiplication cannot wrap around for any budget or spend total a real user could have.
 */
@Component
public class BudgetThresholdService {

    static final String NOTIFICATION_TOPIC = "notification.push.v1";
    static final String NOTIFICATION_CATEGORY = "BUDGET";

    private final BudgetRepository budgets;
    private final MonthlySpendRepository monthlySpend;
    private final OutboxAppender outbox;

    public BudgetThresholdService(BudgetRepository budgets, MonthlySpendRepository monthlySpend,
            OutboxAppender outbox) {
        this.budgets = budgets;
        this.monthlySpend = monthlySpend;
        this.outbox = outbox;
    }

    /**
     * @param occurredAt the triggering settlement event's own event-time timestamp - never
     *                   {@code Instant.now()}, per the phase's "no now() in a projection" rule.
     */
    @Transactional
    public void checkThresholds(UUID userId, LocalDate month, String category, String currency, Instant occurredAt) {
        Budget budget = budgets.findByUserIdAndCategoryAndMonthAndCurrency(userId, category, month, currency)
                .orElse(null);
        if (budget == null) {
            // No budget set for this bucket - nothing to compare against, per point 2 of the task
            // spec.
            return;
        }

        long totalMinor = currentTotal(userId, month, category, currency);
        long limitMinor = budget.getLimitMinor();
        boolean changed = false;

        if (budget.getAlertedAt80() == null && crossed(totalMinor, limitMinor, 80)) {
            publish(userId, budget, totalMinor, 80);
            budget.markAlertedAt80(occurredAt);
            changed = true;
        }
        // Independent of the 80% branch above, deliberately: a jump that clears both thresholds at
        // once must still fire both, not just the higher one.
        if (budget.getAlertedAt100() == null && crossed(totalMinor, limitMinor, 100)) {
            publish(userId, budget, totalMinor, 100);
            budget.markAlertedAt100(occurredAt);
            changed = true;
        }

        if (changed) {
            budgets.save(budget);
        }
    }

    private long currentTotal(UUID userId, LocalDate month, String category, String currency) {
        List<MonthlySpend> bucketsForMonthAndCategory = monthlySpend.findByUserIdAndMonthAndCategory(userId, month,
                category);
        return bucketsForMonthAndCategory.stream()
                .filter(bucket -> bucket.getCurrency().equals(currency))
                .findFirst()
                .map(MonthlySpend::getTotalMinor)
                .orElse(0L);
    }

    private static boolean crossed(long totalMinor, long limitMinor, int percent) {
        return totalMinor * 100 >= limitMinor * percent;
    }

    private void publish(UUID userId, Budget budget, long totalMinor, int percent) {
        String monthName = budget.getMonth().getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String title = "Budget alert: " + budget.getCategory();
        String body = "You've used " + percent + "% of your " + budget.getCategory() + " budget for " + monthName
                + " " + budget.getMonth().getYear() + ".";
        outbox.append(NOTIFICATION_TOPIC, userId.toString(),
                new NotificationPushV1(userId, NOTIFICATION_CATEGORY, title, body));
    }
}
