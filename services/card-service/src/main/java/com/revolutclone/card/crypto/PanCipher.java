package com.revolutclone.card.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * AES-GCM at rest for the PAN and CVV columns, key from {@code CARD_ENCRYPTION_KEY} - see
 * plans/phase-05-cards.md's PAN handling note: "Encrypt with AES-GCM using a key from an
 * environment variable ... This is not PCI compliance and this plan is not pretending it is -
 * it is the minimum shape so the code does not teach you a habit you would have to unlearn."
 *
 * <p>Ciphertext is stored as {@code iv || ciphertext+tag}, the IV prepended rather than kept in
 * a separate column, so a single {@code bytea} column round-trips without a schema change. A
 * fresh random IV is generated per call - GCM's confidentiality guarantee depends on never
 * reusing an (key, IV) pair.
 */
@Component
public class PanCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public PanCipher(CardCryptoProperties properties) {
        this.key = new SecretKeySpec(Base64.getDecoder().decode(properties.encryptionKey()), "AES");
    }

    public byte[] encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv).put(ciphertext);
            return buffer.array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt", e);
        }
    }

    public String decrypt(byte[] ivAndCiphertext) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(ivAndCiphertext);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt", e);
        }
    }
}
