package com.revolutclone.fx.readmodel;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerAccountRefRepository extends JpaRepository<LedgerAccountRef, UUID> {

    /** The mapping is 1:1 - ledger-service keeps a unique index on its own {@code external_account_id}. */
    Optional<LedgerAccountRef> findByAccountId(UUID accountId);
}
