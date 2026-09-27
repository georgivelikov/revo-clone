package com.revolutclone.card.web.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * A card id that does not exist and a card id that belongs to somebody else produce the
 * identical 404 - same reasoning as payment-service's {@code SourceAccountNotFoundException}:
 * a distinguishing 403 would turn the reveal endpoint into an oracle for probing which card ids
 * are real.
 */
public class CardNotFoundException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/card-not-found");

    public CardNotFoundException(UUID cardId) {
        super(HttpStatus.NOT_FOUND, TYPE, "Card not found",
                "No card " + cardId + " belongs to the authenticated user.");
    }
}
