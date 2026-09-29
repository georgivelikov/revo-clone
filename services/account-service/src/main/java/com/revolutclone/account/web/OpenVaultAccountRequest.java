package com.revolutclone.account.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/** The body of {@code POST /internal/accounts/vaults} - see {@link InternalVaultAccountController}. */
public record OpenVaultAccountRequest(
        @NotNull UUID userId,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
        @NotBlank String name) {
}
