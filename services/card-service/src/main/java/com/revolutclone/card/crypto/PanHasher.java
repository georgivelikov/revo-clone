package com.revolutclone.card.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Derives the {@code card.pan_hash} lookup key: a keyed HMAC-SHA256, not a bare hash. Key comes
 * from {@code CARD_PAN_HASH_KEY} - see plans/phase-05-cards.md: "Use a keyed HMAC rather than a
 * bare hash, so the hash column is not itself a brute-forceable index of card numbers (the
 * search space of a PAN is small enough to matter)." Without the key, an attacker with read
 * access to the table could recover a PAN by hashing every candidate and comparing; the key
 * makes that infeasible without also compromising the secret.
 */
@Component
public class PanHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public PanHasher(CardCryptoProperties properties) {
        this.key = new SecretKeySpec(Base64.getDecoder().decode(properties.panHashKey()), ALGORITHM);
    }

    public byte[] hash(String pan) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(pan.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to hash PAN", e);
        }
    }
}
