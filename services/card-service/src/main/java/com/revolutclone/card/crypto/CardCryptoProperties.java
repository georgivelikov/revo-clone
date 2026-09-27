package com.revolutclone.card.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Backs {@code revolut.card.crypto.*} - both keys come from the environment
 * ({@code CARD_ENCRYPTION_KEY}, {@code CARD_PAN_HASH_KEY}; see application.yml), each a
 * base64-encoded 32-byte value. Two separate keys, deliberately: encryption is reversible, the
 * hash key must not be, so a leak of one key must not compromise the other. See
 * plans/phase-05-cards.md's PAN handling note.
 */
@ConfigurationProperties(prefix = "revolut.card.crypto")
public record CardCryptoProperties(String encryptionKey, String panHashKey) {
}
