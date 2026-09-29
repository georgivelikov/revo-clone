package com.revolutclone.analytics.readmodel;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardAuthorizationFactRepository extends JpaRepository<CardAuthorizationFact, UUID> {

    /**
     * Every authorization a user has ever had with one merchant, regardless of state - task
     * 06.4's retro-apply starting point when a user sets an override for that merchant. Case
     * insensitive, matching {@code MerchantCategoryOverrideRepository}'s own lookup: an override
     * is keyed by merchant name without regard to the casing the network happened to send.
     */
    List<CardAuthorizationFact> findByUserIdAndMerchantNameIgnoreCase(UUID userId, String merchantName);

    /**
     * Task 06.8's category drill-down: the settled facts, within one month, for a set of
     * authorization ids already narrowed to one user's one category by
     * {@code TransactionCategoryRepository}. {@code settledAt} is null for anything that never
     * settled (an authorization alone, or a reversal), so those are excluded by the range
     * comparison without a separate null check - a fact whose {@code settledAt} is null can never
     * satisfy {@code between :from and :to}.
     */
    @Query("""
            select f from CardAuthorizationFact f
            where f.authorizationId in :authorizationIds and f.settledAt >= :from and f.settledAt < :to
            """)
    List<CardAuthorizationFact> findSettledByAuthorizationIdsAndPeriod(
            @Param("authorizationIds") Collection<UUID> authorizationIds, @Param("from") Instant from,
            @Param("to") Instant to);

    /**
     * Task 06.8's top-merchants source: every fact this user actually settled within one month,
     * regardless of category - aggregated by (merchant, currency) in {@code AnalyticsQueryService}.
     */
    @Query("""
            select f from CardAuthorizationFact f
            where f.userId = :userId and f.settledAt >= :from and f.settledAt < :to
            """)
    List<CardAuthorizationFact> findSettledByUserIdAndPeriod(@Param("userId") UUID userId,
            @Param("from") Instant from, @Param("to") Instant to);
}
