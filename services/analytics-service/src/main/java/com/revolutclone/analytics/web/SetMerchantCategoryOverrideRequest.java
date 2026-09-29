package com.revolutclone.analytics.web;

import jakarta.validation.constraints.NotBlank;

/** Task 06.2's plain endpoint shape for setting a user override - no test pins it, see the task's scope note. */
public record SetMerchantCategoryOverrideRequest(@NotBlank String merchantName, @NotBlank String category) {
}
