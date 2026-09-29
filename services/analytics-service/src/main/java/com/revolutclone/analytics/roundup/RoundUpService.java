package com.revolutclone.analytics.roundup;

import com.revolutclone.analytics.payment.InitiatePaymentCommand;
import com.revolutclone.analytics.payment.PaymentInitiationClient;
import com.revolutclone.analytics.payment.PaymentRejectedException;
import com.revolutclone.analytics.payment.PaymentServiceUnavailableException;
import com.revolutclone.analytics.vault.Vault;
import com.revolutclone.analytics.vault.VaultRepository;
import com.revolutclone.contracts.events.AuthorizationSettledV1;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * plans/phase-06-vaults-analytics.md's "Round-ups" section (task 06.7): "spend 4.37 EUR -> round
 * to 5.00 -> transfer 0.63 EUR from wallet to vault", "[e]mitted as a normal transfer through
 * payment-service, so it flows through the same saga". Called only from
 * {@link com.revolutclone.analytics.consumer.AuthorizationSettledEventHandler} - never from an
 * approval (a hold might still reverse, per the phase's own warning) and never from a reversal (no
 * money ever moved) - see {@code RoundUpOnSettlementOnlyTest}.
 *
 * <p><strong>What "skip silently on overdraw" actually means here.</strong> There is no
 * synchronous "would this overdraw?" check available anywhere outside ledger-service: insufficient
 * funds is a ledger-level outcome, discovered asynchronously after payment-service has already
 * accepted the transfer (202/PENDING), once the ledger itself rejects the posting via
 * {@code EntryRejectedV1} and the payment ends {@code FAILED}. So "skip rather than fail" cannot
 * mean "detect insufficient funds up front and refuse to attempt the transfer" - that capability
 * does not exist in this system. It means the round-up transfer, once initiated, is an ordinary
 * fire-and-forget payment exactly like any other: if it later fails, for insufficient funds or any
 * other reason, that is an ordinary {@code FAILED} payment visible in the normal feed, not
 * something this class detects, retries, or reports on.
 *
 * <p>What this class <strong>is</strong> responsible for is making sure a synchronous failure of
 * the initiation call itself (a frozen wallet, payment-service unreachable) can never escape and
 * fail the settlement processing that triggered it - see {@link #initiateRoundUp}. That is the
 * whole of {@code RoundUpSkipsOnInsufficientFundsTest}: it configures the fake client to reject at
 * the initiation boundary (standing in for the one synchronous failure this system can produce)
 * and asserts that failure never escapes, and that the settlement's primary projections
 * (categorisation, {@code monthly_spend}) land regardless.
 *
 * <p>The idempotency key handed to payment-service is namespaced by both vault id and
 * authorization id ({@code "roundup:<vaultId>:<authorizationId>"}) - the same namespacing
 * {@link com.revolutclone.analytics.vault.VaultTransferService} already uses for its own transfers
 * - for two reasons: a redelivery of the same settlement event must not double-transfer into a
 * given vault, and a user can plausibly set {@code roundupSourceCardId} to the same card on more
 * than one vault, so two round-ups from the one settlement must not collide with each other's
 * idempotency key either.
 */
@Component
public class RoundUpService {

    private static final Logger log = LoggerFactory.getLogger(RoundUpService.class);

    private final VaultRepository vaults;
    private final PaymentInitiationClient payments;

    public RoundUpService(VaultRepository vaults, PaymentInitiationClient payments) {
        this.vaults = vaults;
        this.payments = payments;
    }

    /**
     * No matching vault, or a round-up of zero, is silently a no-op - neither is an error, and
     * neither creates a transfer (a zero-amount payment is never sent, per {@code RoundUpTest}'s
     * "5.00 -> zero round-up" case).
     */
    public void applyRoundUp(AuthorizationSettledV1 event) {
        List<Vault> matches = vaults.findByUserId(event.userId()).stream()
                .filter(Vault::isRoundupEnabled)
                .filter(vault -> event.cardId().equals(vault.getRoundupSourceCardId()))
                .toList();
        if (matches.isEmpty()) {
            return;
        }

        long roundUpMinor = RoundUp.compute(event.settledMinor(), event.settledCurrency());
        if (roundUpMinor <= 0) {
            return;
        }

        for (Vault vault : matches) {
            initiateRoundUp(event, vault, roundUpMinor);
        }
    }

    private void initiateRoundUp(AuthorizationSettledV1 event, Vault vault, long roundUpMinor) {
        try {
            payments.initiate(new InitiatePaymentCommand(
                    event.userId(),
                    "roundup:" + vault.getId() + ":" + event.authorizationId(),
                    event.accountId(),
                    vault.getAccountId(),
                    roundUpMinor,
                    event.settledCurrency(),
                    "Round-up: " + vault.getName()));
        } catch (PaymentRejectedException | PaymentServiceUnavailableException e) {
            // The documented overdraw-skip rule - see this class's own javadoc for why this is the
            // only place insufficient funds can ever be observed from analytics-service.
            log.warn("round-up transfer for authorization {} into vault {} was not accepted ({}); "
                            + "skipping - the settlement itself is unaffected",
                    event.authorizationId(), vault.getId(), e.getMessage());
        } catch (RuntimeException e) {
            // Belt and braces: a best-effort side effect of a primary action must never be able to
            // fail that action, even via a failure this port's declared contract does not name.
            log.warn("round-up transfer for authorization {} into vault {} failed unexpectedly ({}); skipping",
                    event.authorizationId(), vault.getId(), e.getMessage(), e);
        }
    }
}
