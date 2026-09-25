package com.revolutclone.payment.web.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * The source account is not in the caller's own set of accounts - either it does not exist in
 * the local read model at all, or it belongs to somebody else. <strong>Both cases produce the
 * same 404</strong>, which is the whole point: a 403 for "exists but is not yours" and a 404
 * for "does not exist" would turn this endpoint into an oracle for probing which account ids
 * are real. Same reasoning, and the same status, as account-service's own
 * {@code AccountNotFoundException}.
 *
 * <p>This is the check {@code CrossUserAuthTest} exercises: "cannot send from an account you do
 * not own". It rests on the authenticated JWT subject, resolved by {@code @CurrentUser}, never
 * on anything in the request body - a caller cannot name their own initiator.
 */
public class SourceAccountNotFoundException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/source-account-not-found");

    public SourceAccountNotFoundException(UUID sourceAccountId) {
        super(HttpStatus.NOT_FOUND, TYPE, "Source account not found",
                "No account " + sourceAccountId + " belongs to the authenticated user.");
    }
}
