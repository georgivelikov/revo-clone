package com.revolutclone.analytics.vault;

import com.revolutclone.analytics.payment.InitiatePaymentCommand;
import com.revolutclone.analytics.payment.InitiatedPayment;
import com.revolutclone.analytics.payment.PaymentInitiationClient;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Move-in/move-out (task 06.6): "moving money in or out is an ordinary journal entry between two
 * accounts the same user owns. No new money mechanics at all" - plans/phase-06-vaults-analytics.md's
 * "Vaults" section. This class only decides which account is the source and which is the target
 * for the direction asked; every validation that matters for the transfer itself (funds, currency
 * match, account ownership, active state) is payment-service's job, same as every other caller of
 * its transfer endpoint - it is never re-derived here.
 *
 * <p>The idempotency key handed to payment-service is namespaced by vault id
 * ({@code "vault:<vaultId>:<callerKey>"}), not the caller's header value verbatim: payment-service's
 * own uniqueness guarantee is {@code unique(initiator_user_id, idempotency_key)}, so an unrelated
 * transfer this same user later makes with the same client-chosen header value must not collide
 * with a vault transfer that happened to reuse it. Same reasoning as {@code PaymentInitiator}'s own
 * {@code fxQuoteIdempotencyKey} namespacing.
 */
@Component
public class VaultTransferService {

    private final VaultRepository vaults;
    private final PaymentInitiationClient payments;

    public VaultTransferService(VaultRepository vaults, PaymentInitiationClient payments) {
        this.vaults = vaults;
        this.payments = payments;
    }

    public InitiatedPayment transfer(UUID vaultId, UUID userId, TransferDirection direction, UUID walletAccountId,
            long amountMinor, String currency, String idempotencyKey) {
        Vault vault = vaults.findByIdAndUserId(vaultId, userId)
                .orElseThrow(() -> new VaultNotFoundException(vaultId));

        UUID sourceAccountId = direction == TransferDirection.IN ? walletAccountId : vault.getAccountId();
        UUID targetAccountId = direction == TransferDirection.IN ? vault.getAccountId() : walletAccountId;

        return payments.initiate(new InitiatePaymentCommand(
                userId,
                "vault:" + vaultId + ":" + idempotencyKey,
                sourceAccountId,
                targetAccountId,
                amountMinor,
                currency,
                "Vault " + (direction == TransferDirection.IN ? "deposit" : "withdrawal") + ": " + vault.getName()));
    }
}
