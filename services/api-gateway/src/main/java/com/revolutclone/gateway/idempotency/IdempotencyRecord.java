package com.revolutclone.gateway.idempotency;

import java.util.Base64;
import java.util.Optional;

/**
 * What the gateway stores in Redis under {@code idem:<sub>:<key>}: the request-body hash that
 * makes a same-key-different-body request detectable, plus - once the first request has been
 * answered - the response to replay verbatim for any later duplicate.
 *
 * <p>Encoded as newline-separated fields rather than JSON on purpose. The marker has to carry an
 * arbitrary response body, and embedding one JSON document inside another means escaping,
 * unescaping, and a dependency on a mapper being on this WebFlux-only module's classpath for
 * what is a private, internal, single-writer/single-reader format. Base64 for the body removes
 * the only field that could contain a newline, so decoding is a plain {@code split}. A leading
 * version token means a future field can be added without a Redis flush turning into an
 * exception - an unrecognised version decodes as absent, i.e. the request is simply treated as
 * new, which is safe because layer 2 (the unique constraint in payment-service) still catches
 * the duplicate.
 *
 * @param state       whether the first request is still in flight or has been answered
 * @param bodyHash    SHA-256 of the raw request body, hex-encoded
 * @param status      the stored HTTP status, 0 while {@link State#IN_PROGRESS}
 * @param contentType the stored response {@code Content-Type}, empty while in progress
 * @param body        the stored response body, empty while in progress. A {@code byte[]} in a
 *                    record gives identity-based {@code equals}; these records are never
 *                    compared, only encoded and decoded.
 */
record IdempotencyRecord(State state, String bodyHash, int status, String contentType, byte[] body) {

    enum State {
        IN_PROGRESS,
        COMPLETED
    }

    private static final String VERSION = "v1";
    private static final String SEPARATOR = "\n";
    private static final int FIELD_COUNT = 6;
    private static final byte[] NO_BODY = new byte[0];

    static IdempotencyRecord inProgress(String bodyHash) {
        return new IdempotencyRecord(State.IN_PROGRESS, bodyHash, 0, "", NO_BODY);
    }

    static IdempotencyRecord completed(String bodyHash, int status, String contentType, byte[] body) {
        return new IdempotencyRecord(State.COMPLETED, bodyHash, status,
                contentType == null ? "" : contentType, body);
    }

    String encode() {
        return String.join(SEPARATOR,
                VERSION,
                state.name(),
                bodyHash,
                Integer.toString(status),
                contentType,
                Base64.getEncoder().encodeToString(body));
    }

    /**
     * Decodes a stored marker, or {@link Optional#empty()} if it is not one this version
     * understands. Never throws: a marker that cannot be read must degrade to "no marker", not to
     * a 500 on a money-moving request.
     */
    static Optional<IdempotencyRecord> decode(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String[] fields = raw.split(SEPARATOR, FIELD_COUNT);
        if (fields.length != FIELD_COUNT || !VERSION.equals(fields[0])) {
            return Optional.empty();
        }
        try {
            return Optional.of(new IdempotencyRecord(
                    State.valueOf(fields[1]),
                    fields[2],
                    Integer.parseInt(fields[3]),
                    fields[4],
                    Base64.getDecoder().decode(fields[5])));
        } catch (IllegalArgumentException notOurFormat) {
            return Optional.empty();
        }
    }
}
