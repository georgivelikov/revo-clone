package com.revolutclone.analytics.payment;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * payment-service could not be reached, or answered with a server error, while initiating a
 * transfer. 503, not 500: this is payment-service's problem, not the caller's.
 */
public class PaymentServiceUnavailableException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/payment-service-unavailable");

    public PaymentServiceUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, TYPE, "Payment service unavailable", message);
    }
}
