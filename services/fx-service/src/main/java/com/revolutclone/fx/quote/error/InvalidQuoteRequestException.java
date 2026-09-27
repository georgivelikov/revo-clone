package com.revolutclone.fx.quote.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * The request itself does not describe an exchange: the same currency on both sides, neither amount
 * given, both amounts given, or a buy amount so small that no whole minor unit of the sell currency
 * would buy it.
 *
 * <p>Separate from bean validation because these are relationships between fields rather than
 * properties of one - {@code @Positive} cannot express "exactly one of these two".
 */
public class InvalidQuoteRequestException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/fx-invalid-quote-request");

    public InvalidQuoteRequestException(String detail) {
        super(HttpStatus.BAD_REQUEST, TYPE, "Invalid quote request", detail);
    }
}
