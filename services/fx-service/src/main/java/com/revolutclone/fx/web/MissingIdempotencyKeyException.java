package com.revolutclone.fx.web;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * CLAUDE.md rule 3: every money-moving POST takes an {@code Idempotency-Key} header and honours it.
 * Quoting reserves funds, so it counts. Rejected rather than defaulted to a generated key, which would
 * make every retry a new quote and a second hold - see payment-service's identical exception.
 */
public class MissingIdempotencyKeyException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/missing-idempotency-key");

    public MissingIdempotencyKeyException() {
        super(HttpStatus.BAD_REQUEST, TYPE, "Missing Idempotency-Key",
                "POST /api/fx/quotes requires an Idempotency-Key header");
    }
}
