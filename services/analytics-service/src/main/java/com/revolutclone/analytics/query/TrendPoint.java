package com.revolutclone.analytics.query;

import java.time.LocalDate;

/**
 * One month's overall spend, for one currency - task 06.8's 6-month trend. "Overall" here means
 * summed across every category for that month, deliberately not a per-category series: the
 * task's own wording ("a single overall total per month is the simpler, defensible reading") and
 * nothing else in the phase needs a heavier shape. Still never summed across currencies: a month
 * with spend in two currencies produces two {@code TrendPoint}s for that month, one per currency.
 */
public record TrendPoint(LocalDate month, String currency, long totalMinor, int txnCount) {
}
