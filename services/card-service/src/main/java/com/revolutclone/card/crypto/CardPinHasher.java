package com.revolutclone.card.crypto;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Hashes the card's own PIN (see V4__card_pin.sql) - the step-up credential the reveal endpoint
 * checks before ever decrypting a PAN. BCrypt, not the keyed HMAC {@link PanHasher} uses: a PIN
 * is not a lookup key, so a per-hash random salt (which BCrypt already handles internally) is
 * what matters here, not a shared secret. A PIN's low entropy (4-6 digits) is the reason to hash
 * it at all rather than compare in clear - this is the minimum shape, not a claim that BCrypt
 * alone defeats an offline attacker with the hash; see plans/phase-05-cards.md's "Don't build
 * real PCI" note.
 */
@Component
public class CardPinHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public String hash(String pin) {
        return encoder.encode(pin);
    }

    public boolean matches(String pin, String pinHash) {
        return encoder.matches(pin, pinHash);
    }
}
