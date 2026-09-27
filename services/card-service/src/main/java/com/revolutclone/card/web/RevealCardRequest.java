package com.revolutclone.card.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** The body of {@code POST /api/cards/{cardId}/reveal} - the step-up credential itself. */
public record RevealCardRequest(

        @NotNull(message = "pin is required")
        @Pattern(regexp = "^\\d{4,6}$", message = "pin must be 4 to 6 digits")
        String pin) {
}
