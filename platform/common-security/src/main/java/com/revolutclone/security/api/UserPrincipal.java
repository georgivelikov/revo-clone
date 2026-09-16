package com.revolutclone.security.api;

import java.util.Set;

/** What a controller gets via {@link CurrentUser} - it never touches the raw {@code Jwt}. */
public record UserPrincipal(String userId, String email, Set<String> roles) {
}
