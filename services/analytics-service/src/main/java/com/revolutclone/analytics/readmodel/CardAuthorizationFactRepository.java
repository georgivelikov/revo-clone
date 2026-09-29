package com.revolutclone.analytics.readmodel;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardAuthorizationFactRepository extends JpaRepository<CardAuthorizationFact, UUID> {

    /**
     * Every authorization a user has ever had with one merchant, regardless of state - task
     * 06.4's retro-apply starting point when a user sets an override for that merchant. Case
     * insensitive, matching {@code MerchantCategoryOverrideRepository}'s own lookup: an override
     * is keyed by merchant name without regard to the casing the network happened to send.
     */
    List<CardAuthorizationFact> findByUserIdAndMerchantNameIgnoreCase(UUID userId, String merchantName);
}
