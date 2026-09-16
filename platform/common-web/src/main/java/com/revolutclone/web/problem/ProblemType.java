package com.revolutclone.web.problem;

import java.net.URI;

/**
 * Stable {@code type} URIs for the problem classes this module maps on its own, before any
 * service-specific {@link ApiException} exists. One URI per error class, per RFC 7807.
 */
public final class ProblemType {

    private static final String BASE = "https://revolutclone.com/problems/";

    public static final URI VALIDATION_ERROR = URI.create(BASE + "validation-error");
    public static final URI MESSAGE_NOT_READABLE = URI.create(BASE + "malformed-request");
    public static final URI INTERNAL_ERROR = URI.create(BASE + "internal-error");

    private ProblemType() {
    }
}
