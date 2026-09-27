package com.revolutclone.payment.service;

import com.revolutclone.payment.readmodel.AccountView;

/**
 * The result of {@link PaymentTransactionWriter#validate}: both accounts, already confirmed
 * ownable, active and distinct. {@link #isCrossCurrency()} is what {@link PaymentInitiator}
 * branches on to decide whether a quote is needed before anything is persisted -
 * plans/phase-04-fx-multicurrency.md task 8.
 */
record ValidatedTransfer(AccountView source, AccountView target) {

    boolean isCrossCurrency() {
        return !source.getCurrency().equals(target.getCurrency());
    }
}
