package com.revolutclone.analytics.query;

import com.revolutclone.analytics.categorisation.Category;
import com.revolutclone.analytics.categorisation.InvalidCategoryException;
import com.revolutclone.analytics.categorisation.TransactionCategoryRepository;
import com.revolutclone.analytics.readmodel.CardAuthorizationFact;
import com.revolutclone.analytics.readmodel.CardAuthorizationFactRepository;
import com.revolutclone.analytics.spend.MonthlySpend;
import com.revolutclone.analytics.spend.MonthlySpendRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.AbstractMap.SimpleEntry;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task 06.8's four read endpoints over the projections tasks 06.1-06.7 already write - see
 * {@code AnalyticsQueryController}. This is a pure query layer: it never writes, and every method
 * is scoped to one {@code userId} taken from the caller's own JWT, never from a path parameter
 * another user could substitute. That is precisely why none of the four needs
 * account-service's/vault-service's "404, never 403, for someone else's id" precedent
 * ({@code AccountController}, {@code VaultNotFoundException}): a category name, a month and a
 * period are not ids another user could own, so a query with no matching data simply comes back
 * empty - the drill-down for a category the user never spent in, or the summary for a month with
 * no activity, is a valid empty answer, not a not-found. {@code categoryDrillDown} still 400s for
 * a category outside the fixed set (see {@link InvalidCategoryException}), the same guard the
 * override-write path already uses - that is a malformed request, not a scoping question.
 *
 * <p><strong>Multi-currency.</strong> plans/phase-06-vaults-analytics.md, verbatim: "Do not
 * silently sum across currencies." Every total below stays broken down per currency - see each
 * method's own javadoc. No {@code reportingCurrency} conversion is built here: that would require
 * a new cross-service call to fx-service for month-end rates, which is not currently wired from
 * this service at all, and the phase's own "Risks & gotchas" names this exact phase as having the
 * highest scope-creep risk. Deliberately deferred - see task 06.8's note in {@code agent/state.json}.
 */
@Service
public class AnalyticsQueryService {

    private final MonthlySpendRepository monthlySpend;
    private final TransactionCategoryRepository transactionCategories;
    private final CardAuthorizationFactRepository authorizationFacts;

    public AnalyticsQueryService(MonthlySpendRepository monthlySpend,
            TransactionCategoryRepository transactionCategories,
            CardAuthorizationFactRepository authorizationFacts) {
        this.monthlySpend = monthlySpend;
        this.transactionCategories = transactionCategories;
        this.authorizationFacts = authorizationFacts;
    }

    /**
     * Every {@code (category, currency)} bucket the user actually spent in during {@code month} -
     * read straight off {@code monthly_spend}, which already stores per-currency rows (task 06.3),
     * so this is the honest, always-correct baseline the phase's multi-currency note asks for: no
     * summing, no conversion, just what is actually there.
     */
    @Transactional(readOnly = true)
    public MonthlySummary monthlySummary(UUID userId, LocalDate month) {
        LocalDate monthStart = month.withDayOfMonth(1);
        List<CategorySpend> categories = monthlySpend.findByUserIdAndMonth(userId, monthStart).stream()
                .map(row -> new CategorySpend(row.getCategory(), row.getCurrency(), row.getTotalMinor(),
                        row.getTxnCount()))
                .sorted(Comparator.comparing(CategorySpend::category).thenComparing(CategorySpend::currency))
                .toList();
        return new MonthlySummary(monthStart, categories);
    }

    /**
     * The settled transactions behind one category's slice of one month's donut. Looks up the
     * authorization ids this user has ever assigned to {@code category} (regardless of when), then
     * narrows to the ones that actually settled within {@code month} - an authorization
     * categorised this month but settled last month (or never) is correctly excluded, matching
     * {@code monthly_spend}'s own event-time bucketing.
     */
    @Transactional(readOnly = true)
    public List<DrillDownTransaction> categoryDrillDown(UUID userId, String category, LocalDate month) {
        if (!Category.ALL.contains(category)) {
            throw new InvalidCategoryException(category);
        }
        List<UUID> authorizationIds =
                transactionCategories.findAuthorizationIdsByUserIdAndCategory(userId, category);
        if (authorizationIds.isEmpty()) {
            return List.of();
        }
        LocalDate monthStart = month.withDayOfMonth(1);
        Instant from = monthStart.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = monthStart.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return authorizationFacts.findSettledByAuthorizationIdsAndPeriod(authorizationIds, from, to).stream()
                .map(fact -> new DrillDownTransaction(fact.getAuthorizationId(), fact.getMerchantName(),
                        fact.getSettledMinor(), fact.getBillingCurrency(), fact.getSettledAt()))
                .sorted(Comparator.comparing(DrillDownTransaction::settledAt).reversed())
                .toList();
    }

    /**
     * One overall total per month, for {@code months} months up to and including {@code asOf}'s
     * month - task 06.8's 6-month trend. "Overall" means summed across categories, not a
     * per-category series - the task's own "simpler, defensible reading". Grouping is still by
     * {@code (month, currency)}: a month spent in two currencies produces two {@link TrendPoint}s,
     * never one summed figure. Months with no spend at all are simply absent from the result
     * (no zero-filled entries) - backfilling zeros for an empty chart bar is 06.10's concern.
     */
    @Transactional(readOnly = true)
    public List<TrendPoint> trend(UUID userId, LocalDate asOf, int months) {
        LocalDate endMonth = asOf.withDayOfMonth(1);
        LocalDate startMonth = endMonth.minusMonths(months - 1L);
        List<MonthlySpend> rows = monthlySpend.findByUserIdAndMonthBetween(userId, startMonth, endMonth);
        Map<SimpleEntry<LocalDate, String>, List<MonthlySpend>> byMonthAndCurrency = rows.stream()
                .collect(Collectors.groupingBy(row -> new SimpleEntry<>(row.getMonth(), row.getCurrency())));
        return byMonthAndCurrency.entrySet().stream()
                .map(entry -> new TrendPoint(entry.getKey().getKey(), entry.getKey().getValue(),
                        entry.getValue().stream().mapToLong(MonthlySpend::getTotalMinor).sum(),
                        entry.getValue().stream().mapToInt(MonthlySpend::getTxnCount).sum()))
                .sorted(Comparator.comparing(TrendPoint::month).thenComparing(TrendPoint::currency))
                .toList();
    }

    /**
     * Merchants ranked by total settled spend over {@code month}, for the top-merchants list.
     * Grouped by {@code (merchantName, currency)}: the same merchant paid in two currencies in the
     * same month is two separate entries, ranked and returned independently - never summed.
     */
    @Transactional(readOnly = true)
    public List<TopMerchant> topMerchants(UUID userId, LocalDate month, int limit) {
        LocalDate monthStart = month.withDayOfMonth(1);
        Instant from = monthStart.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = monthStart.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<CardAuthorizationFact> settled = authorizationFacts.findSettledByUserIdAndPeriod(userId, from, to);
        Map<SimpleEntry<String, String>, List<CardAuthorizationFact>> byMerchantAndCurrency = settled.stream()
                .filter(fact -> fact.getMerchantName() != null && fact.getBillingCurrency() != null)
                .collect(Collectors.groupingBy(
                        fact -> new SimpleEntry<>(fact.getMerchantName(), fact.getBillingCurrency())));
        return byMerchantAndCurrency.entrySet().stream()
                .map(entry -> new TopMerchant(entry.getKey().getKey(), entry.getKey().getValue(),
                        entry.getValue().stream().mapToLong(CardAuthorizationFact::getSettledMinor).sum(),
                        entry.getValue().size()))
                .sorted(Comparator.comparingLong(TopMerchant::totalMinor).reversed())
                .limit(limit)
                .toList();
    }
}
