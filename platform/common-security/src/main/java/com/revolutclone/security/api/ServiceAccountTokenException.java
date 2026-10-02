package com.revolutclone.security.api;

/** A client-credentials token could not be obtained - see {@link ServiceAccountTokenProvider}. */
public class ServiceAccountTokenException extends RuntimeException {

    public ServiceAccountTokenException(String message) {
        super(message);
    }

    public ServiceAccountTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
