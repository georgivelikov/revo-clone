package com.revolutclone.simulator.card;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** card-service answered 404 for this card id - relayed as-is rather than mapped to a 500. */
public class UnknownCardException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/card-not-found");

    public UnknownCardException(UUID cardId) {
        super(HttpStatus.NOT_FOUND, TYPE, "Card not found", "No card " + cardId + " exists.");
    }
}
