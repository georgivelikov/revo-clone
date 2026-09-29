package com.revolutclone.analytics.payment;

/**
 * analytics-service's view of payment-service's internal transfer-initiation endpoint
 * ({@code POST /internal/payments}, task 06.6) - the general capability "move money between two
 * accounts the same user owns" that {@link com.revolutclone.analytics.vault.VaultTransferService}
 * uses for vault move-in/move-out today and task 06.7's round-up rule will use next. A port, so a
 * test can substitute an in-memory fake instead of running payment-service - same reasoning as
 * {@link com.revolutclone.analytics.vault.VaultAccountOpener}.
 *
 * <p>Deliberately does not re-validate anything payment-service already validates (funds,
 * currency match, account ownership, active state): "do not duplicate payment-service's transfer
 * validation here - that's payment-service's job, same as every other caller of it"
 * (plans/phase-06-vaults-analytics.md task 6). A rejection surfaces as
 * {@link PaymentRejectedException} exactly as payment-service decided it, not re-derived.
 */
public interface PaymentInitiationClient {

    /**
     * @throws PaymentRejectedException if payment-service refused the transfer outright (a 4xx) -
     *         insufficient funds, a currency mismatch, an unknown or inactive account, and so on
     * @throws PaymentServiceUnavailableException if payment-service could not be reached, or
     *         answered with a server error
     */
    InitiatedPayment initiate(InitiatePaymentCommand command);
}
