package com.revolutclone.ledger.domain;

/**
 * The well-known {@code owner_ref} strings for the four system account types seeded by
 * {@code V5__system_accounts.sql} (task 02.7), one account per {@link LedgerAccountType}
 * per supported currency (EUR, GBP, USD, JPY). Callers look an account up with
 * {@link LedgerAccountRepository#findByOwnerRefAndTypeAndCurrency} using these constants
 * plus a {@link LedgerAccountType} and currency, rather than hand-typing the ref string
 * (a typo here silently returns no account rather than failing loudly at compile time).
 *
 * <p>Not a service or a repository of its own - the four system account types are business
 * concepts later phases (FX in Phase 04, card settlement in Phase 05) will look up and post
 * against, but this class only names them; it does not decide when or how they are used.
 */
public final class SystemAccountRefs {

    public static final String FX_POOL = "system:fx-pool";
    public static final String FEE_INCOME = "system:fee-income";
    public static final String FX_PNL = "system:fx-pnl";
    public static final String EXTERNAL_CLEARING = "system:external-clearing";

    private SystemAccountRefs() {
    }
}
