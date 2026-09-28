package com.revolutclone.web.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * plans/phase-05-cards.md's PAN handling note: "Never log the PAN; add a logback masking
 * converter and a test that asserts a PAN never appears in log output." A run of 12-19 digits is
 * ISO/IEC 7812's PAN length range (card-service's own test BIN generates 16), so this masks any
 * such run wherever it turns up in a logged value - not only in card-service's own messages,
 * since nothing here can enumerate every path a PAN might reach a log line by (an exception's
 * message echoing a request body, for instance). See {@link PanMaskingJsonMembersCustomizer} for
 * where this is actually wired into structured logging.
 *
 * <p>Every digit but the last four is replaced, matching the display convention the rest of this
 * project already uses for a card ({@code panLast4}) - enough to correlate a log line with a
 * support ticket, never enough to reconstruct the number.
 */
public final class PanMasking {

    private static final Pattern PAN_SHAPED_DIGIT_RUN = Pattern.compile("\\b\\d{12,19}\\b");

    private PanMasking() {
    }

    public static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        Matcher matcher = PAN_SHAPED_DIGIT_RUN.matcher(value);
        if (!matcher.find()) {
            return value;
        }

        StringBuilder masked = new StringBuilder(value.length());
        int previousEnd = 0;
        do {
            masked.append(value, previousEnd, matcher.start());
            String digits = matcher.group();
            masked.append("*".repeat(digits.length() - 4)).append(digits.substring(digits.length() - 4));
            previousEnd = matcher.end();
        } while (matcher.find());
        masked.append(value, previousEnd, value.length());
        return masked.toString();
    }
}
