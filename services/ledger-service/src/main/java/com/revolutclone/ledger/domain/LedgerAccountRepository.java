package com.revolutclone.ledger.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

    Optional<LedgerAccount> findByOwnerRefAndTypeAndCurrency(String ownerRef, LedgerAccountType type,
            String currency);
}
