package com.revolutclone.card.authz;

import java.util.UUID;

/**
 * The card's funding wallet is not in card-service's projections yet, so there is no ledger account
 * to hold funds on and no currency to hold them in.
 *
 * <p>Deliberately a failure rather than a decline. The card is good and the holder has done nothing
 * wrong - this service has simply not caught up with {@code account.account.v1} /
 * {@code ledger.entry.v1}. A decline would be final and visible; a thrown exception means the
 * message is retried and succeeds once the projection lands.
 */
public class FundingWalletUnknownException extends RuntimeException {

    public FundingWalletUnknownException(UUID cardId, UUID accountId) {
        super("card " + cardId + " is funded by account " + accountId
                + ", which card-service has no wallet projection for yet");
    }
}
