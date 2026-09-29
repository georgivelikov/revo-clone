package com.revolutclone.analytics.budget;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetRepository extends JpaRepository<Budget, UUID> {

    /** The table's own unique constraint - the lookup both the upsert and threshold checks use. */
    Optional<Budget> findByUserIdAndCategoryAndMonthAndCurrency(UUID userId, String category, LocalDate month,
            String currency);

    List<Budget> findByUserId(UUID userId);
}
