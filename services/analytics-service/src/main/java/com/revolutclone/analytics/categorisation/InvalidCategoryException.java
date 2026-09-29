package com.revolutclone.analytics.categorisation;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/** A category outside the fixed set in {@link Category#ALL} was requested for an override. */
public class InvalidCategoryException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/invalid-category");

    public InvalidCategoryException(String category) {
        super(HttpStatus.BAD_REQUEST, TYPE, "Invalid category",
                "'" + category + "' is not one of the fixed categories: " + Category.ALL);
    }
}
