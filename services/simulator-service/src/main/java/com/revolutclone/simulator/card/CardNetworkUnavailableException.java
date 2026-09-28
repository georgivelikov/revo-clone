package com.revolutclone.simulator.card;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/** card-service could not be reached, or answered with something other than 200/404. */
public class CardNetworkUnavailableException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/card-network-unavailable");

    public CardNetworkUnavailableException(String detail) {
        super(HttpStatus.SERVICE_UNAVAILABLE, TYPE, "Card network unavailable", detail);
    }
}
