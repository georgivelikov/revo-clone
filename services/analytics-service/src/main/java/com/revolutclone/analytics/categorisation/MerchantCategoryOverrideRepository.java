package com.revolutclone.analytics.categorisation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantCategoryOverrideRepository
        extends JpaRepository<MerchantCategoryOverride, MerchantCategoryOverride.Key> {

    /**
     * Case-insensitive on purpose: an authorization's {@code merchantName} is whatever the
     * network sent, and a user setting an override is not expected to reproduce its exact casing.
     */
    Optional<MerchantCategoryOverride> findByUserIdAndMerchantNameIgnoreCase(UUID userId, String merchantName);
}
