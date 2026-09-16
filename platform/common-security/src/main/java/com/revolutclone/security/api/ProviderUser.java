package com.revolutclone.security.api;

/** A user as the identity provider knows it, keyed by its own provider-assigned id. */
public record ProviderUser(String providerUserId, String email, String firstName, String lastName) {
}
