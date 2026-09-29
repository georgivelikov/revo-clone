package com.revolutclone.analytics.testsupport;

import com.revolutclone.analytics.payment.InitiatePaymentCommand;
import com.revolutclone.analytics.payment.InitiatedPayment;
import com.revolutclone.analytics.payment.PaymentInitiationClient;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A fake rather than a real payment-service - same reasoning as {@link FakeVaultAccountOpener}.
 * payment-service's own transfer validation and zero-sum posting are proven where they live
 * ({@code TransferHappyPathTest}, {@code ZeroSumAfterTransfersTest} and friends); this fake exists
 * only to let {@code VaultTransferTest} assert that {@code VaultTransferService} constructs the
 * right {@link InitiatePaymentCommand} for each direction - the plumbing this task actually adds.
 *
 * <p>{@link #failNextCallWith} (task 06.7, {@code RoundUpSkipsOnInsufficientFundsTest}) lets a
 * test simulate the one synchronous failure this system can actually produce at the initiation
 * boundary - a rejection or an unavailable payment-service - standing in for the asynchronous
 * insufficient-funds outcome that, per {@code RoundUpService}'s own javadoc, has no synchronous
 * equivalent anywhere outside ledger-service. The call is still recorded before it throws, since
 * it represents an attempt payment-service genuinely received, not one this client refused to
 * make.
 */
public class FakePaymentInitiationClient implements PaymentInitiationClient {

    private final List<InitiatePaymentCommand> calls = new ArrayList<>();

    private RuntimeException nextFailure;

    @Override
    public synchronized InitiatedPayment initiate(InitiatePaymentCommand command) {
        calls.add(command);
        if (nextFailure != null) {
            RuntimeException failure = nextFailure;
            nextFailure = null;
            throw failure;
        }
        return new InitiatedPayment(UUID.randomUUID(), "PENDING");
    }

    public synchronized List<InitiatePaymentCommand> calls() {
        return List.copyOf(calls);
    }

    /** The next {@link #initiate} call throws {@code exception} instead of succeeding; one-shot. */
    public synchronized void failNextCallWith(RuntimeException exception) {
        this.nextFailure = exception;
    }

    public synchronized void reset() {
        calls.clear();
        nextFailure = null;
    }
}
