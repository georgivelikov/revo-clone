package com.revolutclone.simulator.card;

import java.util.UUID;

/**
 * The port {@link CardSpendService} uses to learn a card's PAN - see
 * {@code CardNetworkPanRestClient} for the HTTP implementation and card-service's
 * {@code NetworkPanController} for why this is the one lawful reason to ask.
 */
public interface CardNetworkPanClient {

    /**
     * @throws UnknownCardException           card-service has no such card
     * @throws CardNetworkUnavailableException card-service could not be reached or answered
     *         unexpectedly
     */
    String panFor(UUID cardId);
}
