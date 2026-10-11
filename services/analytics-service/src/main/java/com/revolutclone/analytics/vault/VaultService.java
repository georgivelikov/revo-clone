package com.revolutclone.analytics.vault;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vault CRUD (task 06.6). {@link #create} opens the vault's backing account on account-service
 * first, outside any local transaction - the same "resolve the remote side, then commit the
 * local row" order every other synchronous cross-service call in this codebase uses (fx-service's
 * quote reservation, card-service's authorization hold): a local transaction cannot roll back an
 * account that account-service has already opened, so nothing here should widen the window in
 * which that call succeeds but the local commit fails.
 */
@Component
public class VaultService {

    private final VaultRepository vaults;
    private final VaultAccountOpener accountOpener;

    public VaultService(VaultRepository vaults, VaultAccountOpener accountOpener) {
        this.vaults = vaults;
        this.accountOpener = accountOpener;
    }

    public Vault create(UUID userId, String name, String currency, Long goalMinor, boolean roundupEnabled,
            UUID roundupSourceCardId) {
        UUID accountId = openOrAdopt(userId, currency, name);
        Vault vault = new Vault(UUID.randomUUID(), userId, accountId, name, currency, goalMinor, roundupEnabled,
                roundupSourceCardId);
        return vaults.save(vault);
    }

    /**
     * A 409 from account-service means the account exists. If a local vault already owns it, that
     * is a real duplicate and stays a 409. If none does, an earlier create opened the account and
     * failed before the local insert - adopt it rather than strand the name forever.
     */
    private UUID openOrAdopt(UUID userId, String currency, String name) {
        try {
            return accountOpener.open(userId, currency, name);
        } catch (DuplicateVaultAccountException duplicate) {
            UUID existing = accountOpener.findExisting(userId, currency, name).orElseThrow(() -> duplicate);
            if (vaults.existsByAccountId(existing)) {
                throw duplicate;
            }
            return existing;
        }
    }

    @Transactional(readOnly = true)
    public List<Vault> list(UUID userId) {
        return vaults.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public Vault get(UUID id, UUID userId) {
        return vaults.findByIdAndUserId(id, userId).orElseThrow(() -> new VaultNotFoundException(id));
    }

    @Transactional
    public Vault update(UUID id, UUID userId, Long goalMinor, boolean roundupEnabled, UUID roundupSourceCardId) {
        Vault vault = get(id, userId);
        vault.updateSettings(goalMinor, roundupEnabled, roundupSourceCardId);
        return vaults.save(vault);
    }
}
