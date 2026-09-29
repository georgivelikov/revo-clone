package com.revolutclone.analytics.readmodel;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryFactRepository extends JpaRepository<LedgerEntryFact, UUID> {
}
