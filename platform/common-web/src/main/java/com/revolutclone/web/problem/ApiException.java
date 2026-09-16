package com.revolutclone.web.problem;

import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * Base for exceptions that should surface as an RFC-7807 {@code ProblemDetail} with a
 * stable, class-specific {@code type} URI. Business exceptions in later services extend
 * this so {@link ApiExceptionHandler} can map them without knowing about them individually.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final URI type;
    private final String title;

    protected ApiException(HttpStatus status, URI type, String title, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public URI type() {
        return type;
    }

    public String title() {
        return title;
    }
}
