package com.revolutclone.card.pan;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Generates 16-digit, Luhn-valid PANs under a fixed test BIN. {@code 999999} is not a real
 * network's issuer identifier - every real scheme's first digit is 3/4/5/6 (major industry
 * identifier: Amex/Diners, Visa, Mastercard, Discover) - so a generated PAN can never collide
 * with, or be mistaken for, a real card number. See plans/phase-05-cards.md's Risks &amp;
 * gotchas: "Don't build real PCI. Encrypt, mask, step-up, move on."
 */
@Component
public class PanGenerator {

    public static final String TEST_BIN = "999999";
    private static final int PAN_LENGTH = 16;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        int[] digits = new int[PAN_LENGTH];
        for (int i = 0; i < TEST_BIN.length(); i++) {
            digits[i] = TEST_BIN.charAt(i) - '0';
        }
        for (int i = TEST_BIN.length(); i < PAN_LENGTH - 1; i++) {
            digits[i] = random.nextInt(10);
        }
        digits[PAN_LENGTH - 1] = checkDigit(digits, PAN_LENGTH - 1);
        return toDigitString(digits);
    }

    /** True if {@code pan} is all digits and satisfies the Luhn checksum. */
    public static boolean isValidLuhn(String pan) {
        if (pan == null || !pan.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int sum = 0;
        boolean doubled = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            int digit = pan.charAt(i) - '0';
            sum += doubled ? doubleDigit(digit) : digit;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }

    /**
     * The Luhn check digit for {@code digits[0..checkIndex-1]}, as if {@code digits[checkIndex]}
     * were the last (rightmost) position of the full number.
     */
    private static int checkDigit(int[] digits, int checkIndex) {
        int sum = 0;
        boolean doubled = true; // the digit immediately left of the check digit doubles first.
        for (int i = checkIndex - 1; i >= 0; i--) {
            sum += doubled ? doubleDigit(digits[i]) : digits[i];
            doubled = !doubled;
        }
        return (10 - (sum % 10)) % 10;
    }

    private static int doubleDigit(int digit) {
        int doubled = digit * 2;
        return doubled > 9 ? doubled - 9 : doubled;
    }

    private static String toDigitString(int[] digits) {
        StringBuilder sb = new StringBuilder(digits.length);
        for (int digit : digits) {
            sb.append((char) ('0' + digit));
        }
        return sb.toString();
    }
}
