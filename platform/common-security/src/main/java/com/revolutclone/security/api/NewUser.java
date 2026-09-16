package com.revolutclone.security.api;

/** What identity-service knows about a user before the provider has assigned an id. */
public record NewUser(String email, String firstName, String lastName) {
}
