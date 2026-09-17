package com.revolutclone.identity.web;

/** Every field optional - only non-null fields are applied. */
public record PatchProfileRequest(String firstName, String lastName, String phone) {
}
