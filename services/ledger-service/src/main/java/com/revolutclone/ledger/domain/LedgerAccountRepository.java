package com.revolutclone.ledger.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

    Optional<LedgerAccount> findByOwnerRefAndTypeAndCurrency(String ownerRef, LedgerAccountType type,
            String currency);

    /**
     * Resolves an {@code account-service} account id to this service's own ledger account - the
     * lookup the {@code PaymentInitiatedV1} consumer needs, since that event names its two sides
     * by account-service account id. See {@code V6__ledger_account_external_ref.sql}.
     */
    Optional<LedgerAccount> findByExternalAccountId(UUID externalAccountId);
}
