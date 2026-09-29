package com.revolutclone.analytics.payment;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * payment-service refused this transfer outright (a 4xx) - its own validation (funds, currency
 * match, account ownership, active state) is authoritative and is never re-derived here. 422,
 * same as payment-service's own {@code PaymentRejectedException}: the request was well-formed,
 * but is not a transfer payment-service will accept.
 */
public class PaymentRejectedException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/vault-transfer-rejected");

    public PaymentRejectedException(String detail) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, TYPE, "Transfer rejected", detail);
    }
}
