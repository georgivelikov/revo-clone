package com.revolutclone.payment.web.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * CLAUDE.md hard rule 3: every money-moving POST takes an {@code Idempotency-Key} header. The
 * header is read as {@code required = false} and checked here rather than declared
 * {@code required = true}, for two reasons: Spring's own {@code MissingRequestHeaderException}
 * would fall through common-web's catch-all and surface as a 500, and a blank-but-present
 * header is just as useless as an absent one while satisfying {@code required = true}.
 */
public class MissingIdempotencyKeyException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/missing-idempotency-key");

    public MissingIdempotencyKeyException() {
        super(HttpStatus.BAD_REQUEST, TYPE, "Missing Idempotency-Key",
                "POST /api/payments requires a non-blank Idempotency-Key header. "
                        + "Generate one client-side and reuse it verbatim on every retry.");
    }
}
