package com.revolutclone.payment.testsupport;

import java.util.UUID;

/**
 * The {@code POST /api/payments} body as literal JSON rather than a serialized
 * {@code CreatePaymentRequest}. Deliberate: building the body with the same record the
 * controller binds would let a rename on both sides pass unnoticed, and these tests are meant
 * to pin the wire contract a browser will actually send.
 */
public final class TransferRequests {

    private TransferRequests() {
    }

    public static String transferBody(UUID sourceAccountId, UUID targetAccountId, long amountMinor,
            String currency, String reference) {
        return """
                {"sourceAccountId":"%s","targetAccountId":"%s","amountMinor":%d,\
                "currency":"%s","reference":"%s"}"""
                .formatted(sourceAccountId, targetAccountId, amountMinor, currency, reference);
    }
}
