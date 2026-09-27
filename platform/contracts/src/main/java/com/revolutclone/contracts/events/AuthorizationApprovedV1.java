package com.revolutclone.contracts.events;

import com.revolutclone.contracts.DomainEvent;

import java.util.UUID;

/**
 * Step 7 of plans/phase-05-cards.md's authorization flow: the issuer said yes and the funds are
 * held. Published by {@code card-service} on {@code card.authorization.v1}, keyed by
 * {@code cardId}.
 *
 * <p><strong>An approval is not an accounting event.</strong> {@code reservationId} names a ledger
 * hold, not a posting - no money has moved, which is exactly why the feed shows this as pending and
 * why available and total balance differ until settlement. The posting arrives later, from the
 * settlement consumer.
 *
 * <p>Carries both amounts: {@code amountMinor}/{@code currency} is what the merchant asked for and
 * {@code billingMinor}/{@code billingCurrency} is what was held on the wallet, so the UI can show
 * "you spent $50, we charged you €46.12" without asking anyone. For a merchant already charging in
 * the wallet's currency the two are identical.
 *
 * <p>{@code panLast4} rather than the PAN: a subscriber (a push notification, an analytics
 * projection) needs to name the card, not to be able to use it.
 */
public record AuthorizationApprovedV1(
        UUID authorizationId,
        UUID cardId,
        UUID userId,
        UUID accountId,
        String panLast4,
        String networkRef,
        String merchantName,
        String merchantMcc,
        String merchantCountry,
        long amountMinor,
        String currency,
        long billingMinor,
        String billingCurrency,
        UUID reservationId) implements DomainEvent {
}
