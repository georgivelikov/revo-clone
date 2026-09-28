package com.revolutclone.card.web.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Distinct from {@link CardNotFoundException}: that one guards a user-facing endpoint where "not
 * found" also has to cover "belongs to somebody else," so its message deliberately says nothing
 * about ownership. {@code /internal/cards/{cardId}/pan-for-network} (task 05.8) has no user in the
 * request at all - the caller is the merchant simulator, not a browser - so there is no probing
 * concern to hide behind a vaguer message here.
 */
public class NetworkCardNotFoundException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/card-not-found");

    public NetworkCardNotFoundException(UUID cardId) {
        super(HttpStatus.NOT_FOUND, TYPE, "Card not found", "No card " + cardId + " exists.");
    }
}
