package com.revolutclone.analytics.categorisation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionCategoryRepository extends JpaRepository<TransactionCategory, UUID> {

    /**
     * Task 06.8's category drill-down starting point: every authorization id this user has ever
     * assigned to one category, regardless of when it was categorised. The caller (see
     * {@code AnalyticsQueryService}) narrows this down to the requested month and to authorizations
     * that actually settled.
     */
    @Query("select c.authorizationId from TransactionCategory c where c.userId = :userId and c.category = :category")
    List<UUID> findAuthorizationIdsByUserIdAndCategory(@Param("userId") UUID userId, @Param("category") String category);
}
