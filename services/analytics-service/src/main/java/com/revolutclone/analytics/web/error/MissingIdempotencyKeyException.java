package com.revolutclone.analytics.web.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * CLAUDE.md hard rule 3: every money-moving POST takes an {@code Idempotency-Key} header - a
 * vault transfer is money-moving even though this service never posts to the ledger itself,
 * since it initiates a real transfer through payment-service. Same precedent as payment-service's
 * own {@code MissingIdempotencyKeyException}: {@code required = false} on the header, checked
 * here rather than {@code required = true}, so a missing header is this problem type rather than
 * a generic 500 from {@code MissingRequestHeaderException}.
 */
public class MissingIdempotencyKeyException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/missing-idempotency-key");

    public MissingIdempotencyKeyException() {
        super(HttpStatus.BAD_REQUEST, TYPE, "Missing Idempotency-Key",
                "POST /api/analytics/vaults/{id}/transfer requires a non-blank Idempotency-Key header. "
                        + "Generate one client-side and reuse it verbatim on every retry.");
    }
}
