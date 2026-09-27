package com.revolutclone.card.readmodel;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a card's {@code account_id} into the wallet a hold can actually be placed on, using only
 * card-service's own two projections. No HTTP call, which is what keeps the authorization decision
 * inside the network's timeout even with account-service and ledger-service both unreachable.
 *
 * <p>An empty result means the projections have not caught up with the account yet, or the card was
 * issued against something that is not a wallet. Neither is a business decline - the network would
 * take a decline as final for a card that is perfectly good - so the caller treats it as a technical
 * failure and lets the message be retried.
 */
@Service
public class FundingWalletResolver {

    private final FundingAccountRepository fundingAccounts;
    private final LedgerAccountRefRepository ledgerAccountRefs;

    public FundingWalletResolver(FundingAccountRepository fundingAccounts,
            LedgerAccountRefRepository ledgerAccountRefs) {
        this.fundingAccounts = fundingAccounts;
        this.ledgerAccountRefs = ledgerAccountRefs;
    }

    @Transactional(readOnly = true)
    public Optional<FundingWallet> resolve(UUID accountId) {
        return fundingAccounts.findById(accountId)
                .filter(FundingAccount::isWallet)
                .flatMap(account -> ledgerAccountRefs.findByAccountId(account.getAccountId())
                        .map(ref -> new FundingWallet(account.getAccountId(), ref.getLedgerAccountId(),
                                account.getCurrency())));
    }
}
