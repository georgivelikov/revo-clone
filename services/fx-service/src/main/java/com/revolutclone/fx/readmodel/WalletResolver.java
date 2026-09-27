package com.revolutclone.fx.readmodel;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns "this caller wants to sell EUR" into the ledger account a hold can actually be placed on,
 * using only fx-service's own two projections - {@link AccountOwner} for the wallet and
 * {@link LedgerAccountRef} for the ledger account behind it. No HTTP call, so a quote can be
 * reserved with account-service down, and no database but this service's own (CLAUDE.md rule 7).
 *
 * <p>An empty result is not an error here; it is one of two situations the caller has to
 * distinguish for itself: the caller has no wallet in that currency at all, or the projections have
 * not caught up with an account that was opened moments ago. Both look the same from here and both
 * mean "cannot quote yet", which is why this returns an {@link Optional} rather than throwing.
 */
@Service
public class WalletResolver {

    private final AccountOwnerRepository accountOwners;
    private final LedgerAccountRefRepository ledgerAccountRefs;

    public WalletResolver(AccountOwnerRepository accountOwners, LedgerAccountRefRepository ledgerAccountRefs) {
        this.accountOwners = accountOwners;
        this.ledgerAccountRefs = ledgerAccountRefs;
    }

    @Transactional(readOnly = true)
    public Optional<UUID> resolveWalletLedgerAccount(UUID userId, String currency) {
        return accountOwners.findByUserIdAndCurrency(userId, currency.toUpperCase(Locale.ROOT)).stream()
                .filter(AccountOwner::isWallet)
                .findFirst()
                .flatMap(owner -> ledgerAccountRefs.findByAccountId(owner.getAccountId()))
                .map(LedgerAccountRef::getLedgerAccountId);
    }
}
