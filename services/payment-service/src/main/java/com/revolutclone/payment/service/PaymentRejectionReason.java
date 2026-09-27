package com.revolutclone.payment.service;

/**
 * Why {@code POST /api/payments} refused to accept a transfer <em>before</em> any row was
 * written. Every one of these is a synchronous 422: the request never became a payment, never
 * reached the outbox, and therefore never reached the ledger.
 *
 * <p>Distinct on purpose from the reasons a payment can end {@code FAILED}, which arrive later
 * on {@code EntryRejectedV1} and land in {@code payment.failure_reason}. A rejection here means
 * "this is not a transfer I can accept"; a failure there means "I accepted it, the ledger
 * refused it" - the classic example being {@code INSUFFICIENT_FUNDS}, which this service
 * cannot and must not try to decide, since it holds no balances.
 */
public enum PaymentRejectionReason {

    /** The source account exists and is the caller's, but is frozen or closed. */
    SOURCE_ACCOUNT_NOT_ACTIVE,

    /** No such account in the local read model - see {@code AccountOpenedEventHandler}. */
    TARGET_ACCOUNT_NOT_FOUND,

    TARGET_ACCOUNT_NOT_ACTIVE,

    /** The request's own {@code currency} does not match the source account's. */
    CURRENCY_MISMATCH,

    /** Source and target are the same account, which would net to nothing. */
    SAME_ACCOUNT,

    /** Above {@code revolut.payments.max-amount-minor}. */
    AMOUNT_ABOVE_LIMIT,

    /**
     * A cross-currency transfer (plans/phase-04-fx-multicurrency.md task 8) could not get a quote:
     * fx-service refused it (no rate, no wallet to credit) or could not be reached at all,
     * including the circuit breaker being open. Nothing was reserved and nothing was written -
     * {@link com.revolutclone.payment.fx.FxQuoteUnavailableException} is thrown before this
     * service's own transaction ever opens.
     */
    FX_QUOTE_UNAVAILABLE
}
