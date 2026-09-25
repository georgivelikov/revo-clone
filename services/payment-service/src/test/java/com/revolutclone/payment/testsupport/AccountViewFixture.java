package com.revolutclone.payment.testsupport;

import com.revolutclone.payment.readmodel.AccountView;
import com.revolutclone.payment.readmodel.AccountViewRepository;
import com.revolutclone.payment.readmodel.AccountViewState;
import java.time.Instant;
import java.util.UUID;

/**
 * Seeds rows into the local account read model directly, which is exactly what
 * {@code AccountOpenedEventHandler} would have produced from an {@code AccountOpenedV1} on
 * {@code account.account.v1} - that projection has its own test
 * ({@code AccountReadModelProjectionTest}) and does not need re-proving through a broker here.
 * What these tests are about is the validation and the write that sit on top of the view.
 */
public final class AccountViewFixture {

    private AccountViewFixture() {
    }

    public static AccountView activeWallet(AccountViewRepository views, UUID userId, String currency) {
        return wallet(views, userId, currency, AccountViewState.ACTIVE);
    }

    public static AccountView wallet(AccountViewRepository views, UUID userId, String currency,
            AccountViewState state) {
        AccountView view = new AccountView(UUID.randomUUID());
        view.apply(userId, currency, "WALLET", state, Instant.now());
        return views.saveAndFlush(view);
    }
}
