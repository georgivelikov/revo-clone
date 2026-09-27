package com.revolutclone.card.readmodel;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerAccountRefRepository extends JpaRepository<LedgerAccountRef, UUID> {

    Optional<LedgerAccountRef> findByAccountId(UUID accountId);
}
