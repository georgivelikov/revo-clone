package com.revolutclone.analytics.categorisation;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionCategoryRepository extends JpaRepository<TransactionCategory, UUID> {
}
