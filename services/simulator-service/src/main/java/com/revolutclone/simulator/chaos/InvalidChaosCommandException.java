package com.revolutclone.simulator.chaos;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/** An unknown {@code lever}, or a known one missing the field it needs (e.g. {@code value}, {@code topic}). */
public class InvalidChaosCommandException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/invalid-chaos-command");

    public InvalidChaosCommandException(String detail) {
        super(HttpStatus.BAD_REQUEST, TYPE, "Invalid chaos command", detail);
    }
}
