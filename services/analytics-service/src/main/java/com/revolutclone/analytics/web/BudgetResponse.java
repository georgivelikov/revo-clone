package com.revolutclone.analytics.web;

import com.revolutclone.analytics.budget.Budget;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BudgetResponse(
        UUID id,
        String category,
        LocalDate month,
        long limitMinor,
        String currency,
        Instant alertedAt80,
        Instant alertedAt100) {

    public static BudgetResponse from(Budget budget) {
        return new BudgetResponse(budget.getId(), budget.getCategory(), budget.getMonth(), budget.getLimitMinor(),
                budget.getCurrency(), budget.getAlertedAt80(), budget.getAlertedAt100());
    }
}
