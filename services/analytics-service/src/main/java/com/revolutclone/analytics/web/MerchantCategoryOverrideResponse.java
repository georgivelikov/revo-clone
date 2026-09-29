package com.revolutclone.analytics.web;

import com.revolutclone.analytics.categorisation.MerchantCategoryOverride;
import java.time.Instant;

public record MerchantCategoryOverrideResponse(String merchantName, String category, Instant updatedAt) {

    public static MerchantCategoryOverrideResponse from(MerchantCategoryOverride override) {
        return new MerchantCategoryOverrideResponse(override.getMerchantName(), override.getCategory(),
                override.getUpdatedAt());
    }
}
