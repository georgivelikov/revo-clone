package com.revolutclone.card.service;

import com.revolutclone.card.crypto.CardPinHasher;
import com.revolutclone.card.crypto.PanCipher;
import com.revolutclone.card.domain.Card;
import com.revolutclone.card.domain.CardRepository;
import com.revolutclone.card.web.error.CardNotFoundException;
import com.revolutclone.card.web.error.StepUpFailedException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * plans/phase-05-cards.md's PAN handling note: "return the full PAN from exactly one endpoint
 * that requires a step-up." This is that endpoint's service. Read-only (no state changes on a
 * reveal), but still transactional so the card lookup and decryption see one consistent view.
 */
@Component
public class CardRevealService {

    private final CardRepository cards;
    private final CardPinHasher pinHasher;
    private final PanCipher panCipher;

    public CardRevealService(CardRepository cards, CardPinHasher pinHasher, PanCipher panCipher) {
        this.cards = cards;
        this.pinHasher = pinHasher;
        this.panCipher = panCipher;
    }

    @Transactional(readOnly = true)
    public RevealedCard reveal(UUID cardId, UUID userId, String pin) {
        Card card = cards.findByIdAndUserId(cardId, userId)
                .orElseThrow(() -> new CardNotFoundException(cardId));

        if (!pinHasher.matches(pin, card.getPinHash())) {
            throw new StepUpFailedException();
        }

        String pan = panCipher.decrypt(card.getPanEncrypted());
        String cvv = panCipher.decrypt(card.getCvvEncrypted());
        return new RevealedCard(pan, cvv, card.getExpiryMonth(), card.getExpiryYear());
    }
}
