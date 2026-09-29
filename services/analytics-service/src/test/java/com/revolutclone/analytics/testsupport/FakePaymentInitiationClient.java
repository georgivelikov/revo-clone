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
 */
public class FakePaymentInitiationClient implements PaymentInitiationClient {

    private final List<InitiatePaymentCommand> calls = new ArrayList<>();

    @Override
    public synchronized InitiatedPayment initiate(InitiatePaymentCommand command) {
        calls.add(command);
        return new InitiatedPayment(UUID.randomUUID(), "PENDING");
    }

    public synchronized List<InitiatePaymentCommand> calls() {
        return List.copyOf(calls);
    }

    public synchronized void reset() {
        calls.clear();
    }
}
