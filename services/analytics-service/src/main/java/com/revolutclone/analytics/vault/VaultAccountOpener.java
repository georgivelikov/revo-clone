package com.revolutclone.analytics.vault;

import java.util.Optional;
import java.util.UUID;

/**
 * analytics-service's view of account-service's one synchronous edge for this task
 * (plans/phase-06-vaults-analytics.md's "Vaults" section): "analytics-service must never call
 * ledger-service directly or post anything itself" - opening the vault's backing account is
 * delegated entirely to account-service. A port, so a test can substitute an in-memory fake
 * instead of running account-service - same reasoning as card-service's {@code LedgerReservations}.
 */
public interface VaultAccountOpener {

    /**
     * Opens a {@code VAULT}-type account for {@code userId} in {@code currency}, named
     * {@code name}.
     *
     * @return the id of the account account-service opened
     * @throws DuplicateVaultAccountException if account-service already has a VAULT account with
     *         this name and currency for this user - its own {@code unique(user_id, currency,
     *         type, name)}
     * @throws AccountServiceUnavailableException if account-service could not be reached, or
     *         answered with a server error
     */
    UUID open(UUID userId, String currency, String name);

    /**
     * The id of the VAULT account account-service already holds for exactly this user, currency
     * and name, if any. Used to recover when {@link #open} 409s because an earlier attempt opened
     * the account but never recorded the local vault row.
     */
    default Optional<UUID> findExisting(UUID userId, String currency, String name) {
        return Optional.empty();
    }
}
