package com.revolutclone.card.service;

import com.revolutclone.card.domain.Card;
import com.revolutclone.card.domain.CardAuthorization;
import com.revolutclone.card.domain.CardAuthorizationRepository;
import com.revolutclone.card.domain.CardRepository;
import com.revolutclone.card.web.error.CardNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Task 05.10's Card screen: the holder's own cards, and one card's spend feed. */
@Component
public class CardQueryService {

    private final CardRepository cards;
    private final CardAuthorizationRepository authorizations;

    public CardQueryService(CardRepository cards, CardAuthorizationRepository authorizations) {
        this.cards = cards;
        this.authorizations = authorizations;
    }

    @Transactional(readOnly = true)
    public List<Card> listCards(UUID userId) {
        return cards.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<CardAuthorization> listAuthorizations(UUID cardId, UUID userId) {
        cards.findByIdAndUserId(cardId, userId).orElseThrow(() -> new CardNotFoundException(cardId));
        return authorizations.findByCardIdOrderByAuthorizedAtDesc(cardId);
    }
}
