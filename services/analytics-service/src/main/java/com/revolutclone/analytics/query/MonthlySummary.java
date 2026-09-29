package com.revolutclone.analytics.query;

import java.time.LocalDate;
import java.util.List;

/**
 * Task 06.8's monthly summary response: every category a user actually spent in during
 * {@code month}, broken down per currency - see {@link CategorySpend}. {@code categories} is
 * empty, not a 404, for a month with no activity: there is no id here another user could own, so
 * there is nothing to distinguish "not found" from "nothing happened".
 */
public record MonthlySummary(LocalDate month, List<CategorySpend> categories) {
}
