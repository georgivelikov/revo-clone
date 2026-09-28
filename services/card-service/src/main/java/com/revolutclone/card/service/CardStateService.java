package com.revolutclone.card.service;

import com.revolutclone.card.domain.Card;
import com.revolutclone.card.domain.CardRepository;
import com.revolutclone.card.domain.CardState;
import com.revolutclone.card.web.error.CardCancelledException;
import com.revolutclone.card.web.error.CardNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * plans/phase-05-cards.md task 9's freeze/unfreeze. Ownership-scoped the same way
 * {@link CardRevealService} is - a card id that exists but belongs to somebody else reads as
 * not-found, never forbidden, so the endpoint cannot be used to probe which card ids are real.
 */
@Component
public class CardStateService {

    private final CardRepository cards;

    public CardStateService(CardRepository cards) {
        this.cards = cards;
    }

    @Transactional
    public Card freeze(UUID cardId, UUID userId) {
        Card card = ownedCard(cardId, userId);
        if (card.getState() == CardState.CANCELLED) {
            throw new CardCancelledException(cardId);
        }
        card.freeze();
        return card;
    }

    @Transactional
    public Card unfreeze(UUID cardId, UUID userId) {
        Card card = ownedCard(cardId, userId);
        if (card.getState() == CardState.CANCELLED) {
            throw new CardCancelledException(cardId);
        }
        card.unfreeze();
        return card;
    }

    private Card ownedCard(UUID cardId, UUID userId) {
        return cards.findByIdAndUserId(cardId, userId).orElseThrow(() -> new CardNotFoundException(cardId));
    }
}
