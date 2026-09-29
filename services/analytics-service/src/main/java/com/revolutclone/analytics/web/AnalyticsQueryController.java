package com.revolutclone.analytics.web;

import com.revolutclone.analytics.query.AnalyticsQueryService;
import com.revolutclone.analytics.query.DrillDownTransaction;
import com.revolutclone.analytics.query.MonthlySummary;
import com.revolutclone.analytics.query.TopMerchant;
import com.revolutclone.analytics.query.TrendPoint;
import com.revolutclone.security.api.CurrentUser;
import com.revolutclone.security.api.UserPrincipal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task 06.8's four query endpoints for the Analytics dashboard (06.10 builds the screen; this is
 * the API alone). Every endpoint is scoped to {@code @CurrentUser} - see
 * {@link AnalyticsQueryService}'s javadoc for why that makes the account-service/vault-service
 * "404, never 403" precedent inapplicable here: none of these four take a resource id another
 * user could own.
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsQueryController {

    /** "A 6-month trend bar chart" - plans/phase-06-vaults-analytics.md's Frontend section. */
    private static final int TREND_MONTHS = 6;

    private static final int DEFAULT_TOP_MERCHANTS_LIMIT = 10;

    private final AnalyticsQueryService queryService;

    public AnalyticsQueryController(AnalyticsQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * Every category's total (per currency) and transaction count for one month, from
     * {@code monthly_spend} - feeds the donut chart. {@code month} may be any day within the
     * target month; the response's own {@code month} is always normalised to the first.
     */
    @GetMapping("/summary")
    public MonthlySummary summary(@CurrentUser UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate month) {
        return queryService.monthlySummary(UUID.fromString(principal.userId()), month);
    }

    /**
     * The settled transactions behind one category/month slice - what a tap on a donut slice
     * drills into. {@code category} must be one of the fixed set (see {@code Category.ALL}); an
     * unrecognised value is a 400, not an empty list.
     */
    @GetMapping("/categories/{category}/transactions")
    public List<DrillDownTransaction> categoryDrillDown(@CurrentUser UserPrincipal principal,
            @PathVariable String category,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate month) {
        return queryService.categoryDrillDown(UUID.fromString(principal.userId()), category, month);
    }

    /**
     * One overall total per month (see {@link AnalyticsQueryService#trend} for why "overall" and
     * not per-category) for the {@value #TREND_MONTHS} months up to and including {@code asOf}'s
     * month. {@code asOf} defaults to today (UTC) when omitted - a live read, not a projection, so
     * using the wall clock here does not violate the phase's "no now() in a projection" rule.
     */
    @GetMapping("/trend")
    public List<TrendPoint> trend(@CurrentUser UserPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now(ZoneOffset.UTC);
        return queryService.trend(UUID.fromString(principal.userId()), effectiveAsOf, TREND_MONTHS);
    }

    /**
     * Merchants ranked by total settled spend over one month, most-spent first. {@code limit}
     * defaults to {@value #DEFAULT_TOP_MERCHANTS_LIMIT}.
     */
    @GetMapping("/merchants/top")
    public List<TopMerchant> topMerchants(@CurrentUser UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate month,
            @RequestParam(required = false) Integer limit) {
        int effectiveLimit = limit != null ? limit : DEFAULT_TOP_MERCHANTS_LIMIT;
        return queryService.topMerchants(UUID.fromString(principal.userId()), month, effectiveLimit);
    }
}
