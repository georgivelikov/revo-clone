package com.revolutclone.card.service;

import com.revolutclone.card.crypto.PanCipher;
import com.revolutclone.card.domain.Card;
import com.revolutclone.card.domain.CardRepository;
import com.revolutclone.card.web.error.NetworkCardNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task 05.8's other half of "the simulator asks the network for a PAN": a real card network never
 * calls anyone to learn a card number, because it already has it from the physical swipe. This
 * service computes what that network would already know, for the one caller in this project that
 * legitimately stands in for it - see {@code NetworkPanController} for why the endpoint carries no
 * step-up and no JWT, unlike {@link CardRevealService}, which this otherwise mirrors.
 *
 * <p>Not gated on card state: a frozen or expired card still has a PAN, and the simulator needs to
 * be able to submit a decline-triggering spend on exactly such a card to exercise those paths.
 */
@Component
public class NetworkPanLookupService {

    private final CardRepository cards;
    private final PanCipher panCipher;

    public NetworkPanLookupService(CardRepository cards, PanCipher panCipher) {
        this.cards = cards;
        this.panCipher = panCipher;
    }

    @Transactional(readOnly = true)
    public String panFor(UUID cardId) {
        Card card = cards.findById(cardId).orElseThrow(() -> new NetworkCardNotFoundException(cardId));
        return panCipher.decrypt(card.getPanEncrypted());
    }
}
