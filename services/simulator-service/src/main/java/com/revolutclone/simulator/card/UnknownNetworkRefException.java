package com.revolutclone.simulator.card;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * A reversal named a {@code networkRef} this simulator never issued, or one it already concluded
 * (settled or reversed) - see {@link SpendRegistry#markReversed}.
 */
public class UnknownNetworkRefException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/unknown-network-ref");

    public UnknownNetworkRefException(String networkRef) {
        super(HttpStatus.NOT_FOUND, TYPE, "Unknown authorization",
                "No pending authorization " + networkRef + " to reverse.");
    }
}
