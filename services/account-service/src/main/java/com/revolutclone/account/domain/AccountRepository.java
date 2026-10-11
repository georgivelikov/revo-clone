package com.revolutclone.account.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByUserId(UUID userId);

    Optional<Account> findByIdAndUserId(UUID id, UUID userId);

    /** The exact tuple behind {@code unique(user_id, currency, type, name)} - at most one row. */
    Optional<Account> findByUserIdAndCurrencyAndTypeAndName(UUID userId, String currency, AccountType type, String name);

    /**
     * Fills in the one column {@link Account} gets after it is first inserted -
     * plans/phase-01-identity-account-gateway.md: "Filled in by Phase 02 once the ledger
     * exists." A targeted {@code UPDATE} rather than {@code load, mutate, save()}: {@link Account}
     * always reports {@code isNew() == true} by design ("an account is only ever inserted once,
     * never updated via this entity"), so routing this through {@code save()} would retry the
     * original {@code persist()} and fail on the primary key. This bypasses that assumption for
     * the one column that is a deliberate, later exception to it.
     *
     * @return 1 if a row was updated, 0 if this account id is unknown
     */
    @Modifying
    @Query("update Account a set a.ledgerAccountId = :ledgerAccountId where a.id = :accountId")
    int applyLedgerAccountId(@Param("accountId") UUID accountId, @Param("ledgerAccountId") UUID ledgerAccountId);
}
