package com.revolutclone.payment.web.error;

import com.revolutclone.payment.service.PaymentRejectionReason;
import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * 422: the request was well-formed and authenticated, but is not a transfer this service will
 * accept - see {@link PaymentRejectionReason}. 422 rather than 400 because nothing about the
 * syntax is wrong; the request is semantically unacceptable against account state the client
 * cannot see.
 *
 * <p>The reason code is repeated at the head of the {@code detail} string so a client can
 * branch on it without parsing prose, and is also exposed via {@link #reason()} for tests.
 */
public class PaymentRejectedException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/payment-rejected");

    private final PaymentRejectionReason reason;

    public PaymentRejectedException(PaymentRejectionReason reason, String detail) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, TYPE, "Payment rejected", reason.name() + ": " + detail);
        this.reason = reason;
    }

    public PaymentRejectionReason reason() {
        return reason;
    }
}
