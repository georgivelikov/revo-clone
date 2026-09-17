package com.revolutclone.identity.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

public class DuplicateEmailException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/duplicate-email");

    public DuplicateEmailException(String email) {
        super(HttpStatus.CONFLICT, TYPE, "Duplicate email",
                "a user with email " + email + " already exists");
    }
}
