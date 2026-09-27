package com.revolutclone.card.web.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * The reveal endpoint's step-up check failed: the supplied PIN does not match the card's stored
 * hash. 403, not 401 - the caller is a genuinely authenticated user (a valid JWT got them past
 * the security filter chain already), just one who has not proven the extra step-up factor this
 * specific action requires. See plans/phase-05-cards.md's Card screen note on step-up.
 */
public class StepUpFailedException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/step-up-failed");

    public StepUpFailedException() {
        super(HttpStatus.FORBIDDEN, TYPE, "Step-up failed",
                "The PIN supplied does not match this card's stored PIN.");
    }
}
