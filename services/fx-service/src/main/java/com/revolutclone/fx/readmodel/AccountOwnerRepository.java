package com.revolutclone.fx.readmodel;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Nothing outside {@code AccountOwnerEventHandler} writes through this repository - a projection
 * has exactly one writer, the consumer that replays the topic.
 */
public interface AccountOwnerRepository extends JpaRepository<AccountOwner, UUID> {

    /**
     * Returns every account the caller holds in this currency, wallets and vaults alike;
     * {@link WalletResolver} picks the wallet. Kept as a list rather than an {@code Optional}
     * because the underlying index is deliberately non-unique - see
     * {@code V5__fx_account_read_model.sql}.
     */
    List<AccountOwner> findByUserIdAndCurrency(UUID userId, String currency);
}
