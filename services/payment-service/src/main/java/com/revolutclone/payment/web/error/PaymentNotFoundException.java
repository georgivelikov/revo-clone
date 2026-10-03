package com.revolutclone.payment.web.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * No payment with this id exists, or one does but the caller is neither its initiator nor the
 * owner of either account it moved money between. Both cases produce the same 404, for the same
 * reason {@link SourceAccountNotFoundException} does: a 403 for "exists but is not yours" would
 * turn {@code GET /api/payments/{id}} into an oracle for probing other users' payment ids.
 */
public class PaymentNotFoundException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/payment-not-found");

    public PaymentNotFoundException(UUID paymentId) {
        super(HttpStatus.NOT_FOUND, TYPE, "Payment not found",
                "No payment " + paymentId + " is visible to the authenticated user.");
    }
}
