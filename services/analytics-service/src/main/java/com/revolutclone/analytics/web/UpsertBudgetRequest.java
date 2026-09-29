package com.revolutclone.analytics.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;

/**
 * Task 06.5's plain upsert shape for setting a budget - no test pins its exact fields, per the
 * task's own scope note. {@code month} may be any day within the target month; the service
 * normalises it to the first of that month itself, matching {@code monthly_spend}'s own convention.
 */
public record UpsertBudgetRequest(
        @NotBlank String category,
        @NotNull LocalDate month,
        @Positive long limitMinor,
        @NotBlank String currency) {
}
