package com.revolutclone.card.service;

/** The decrypted material a successful step-up releases - never persisted, never logged. */
public record RevealedCard(String pan, String cvv, int expiryMonth, int expiryYear) {
}
