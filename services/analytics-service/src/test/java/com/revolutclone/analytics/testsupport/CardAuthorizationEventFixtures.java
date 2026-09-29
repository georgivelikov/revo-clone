package com.revolutclone.analytics.testsupport;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AuthorizationApprovedV1;
import com.revolutclone.contracts.events.AuthorizationSettledV1;
import java.time.Instant;
import java.util.UUID;

/**
 * Builds the two {@code card.authorization.v1} envelopes task 06.3's {@code monthly_spend} tests
 * need: an approval (so {@code TransactionCategorisationService} has something to assign) followed
 * by its settlement (so {@code MonthlySpendProjectionHandler} has something to increment). Mirrors
 * {@code CardAuthorizationProjectionTest}'s own inline construction, pulled out here since three
 * separate test classes need the same pair.
 */
public final class CardAuthorizationEventFixtures {

    private CardAuthorizationEventFixtures() {
    }

    public static EventEnvelope<AuthorizationApprovedV1> approved(UUID authorizationId, UUID cardId, UUID userId,
            UUID accountId, String merchantName, String mcc, long amountMinor, String currency, Instant occurredAt) {
        return new EventEnvelope<>(UUID.randomUUID(), occurredAt, "corr-approve", "AuthorizationApprovedV1", 1,
                new AuthorizationApprovedV1(authorizationId, cardId, userId, accountId, "4242",
                        "net-" + authorizationId, merchantName, mcc, "IE", amountMinor, currency, amountMinor,
                        currency, UUID.randomUUID()));
    }

    /** A fresh delivery (its own {@code eventId}) of a settlement for the given amount/currency. */
    public static EventEnvelope<AuthorizationSettledV1> settled(UUID authorizationId, UUID cardId, UUID userId,
            UUID accountId, String merchantName, String mcc, long settledMinor, String currency, Instant occurredAt) {
        return settled(UUID.randomUUID(), authorizationId, cardId, userId, accountId, merchantName, mcc,
                settledMinor, currency, occurredAt);
    }

    /** Same shape, with an explicit {@code eventId} - what a genuine topic replay redelivers verbatim. */
    public static EventEnvelope<AuthorizationSettledV1> settled(UUID eventId, UUID authorizationId, UUID cardId,
            UUID userId, UUID accountId, String merchantName, String mcc, long settledMinor, String currency,
            Instant occurredAt) {
        return new EventEnvelope<>(eventId, occurredAt, "corr-settle", "AuthorizationSettledV1", 1,
                new AuthorizationSettledV1(authorizationId, cardId, userId, accountId, "4242",
                        "net-" + authorizationId, merchantName, mcc, "IE", settledMinor, currency, settledMinor,
                        currency, 0L, 0L, UUID.randomUUID(), null, "SETTLED"));
    }
}
