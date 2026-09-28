package com.revolutclone.simulator.card;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** A trivial in-memory stand-in for card-service's {@code /internal/cards/{cardId}/pan-for-network}. */
class FakeCardNetworkPanClient implements CardNetworkPanClient {

    private final Map<UUID, String> pansByCardId = new HashMap<>();

    void register(UUID cardId, String pan) {
        pansByCardId.put(cardId, pan);
    }

    @Override
    public String panFor(UUID cardId) {
        String pan = pansByCardId.get(cardId);
        if (pan == null) {
            throw new UnknownCardException(cardId);
        }
        return pan;
    }
}
