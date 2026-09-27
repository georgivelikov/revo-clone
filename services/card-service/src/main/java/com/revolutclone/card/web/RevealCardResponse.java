package com.revolutclone.card.web;

import com.revolutclone.card.service.RevealedCard;

/**
 * The one response in this service allowed to carry a full PAN. Never logged, never cached -
 * see plans/phase-05-cards.md's PAN handling note; the auto-hide-after-30s and
 * never-in-clipboard-history behaviour is the frontend's job (task 10), not this endpoint's.
 */
public record RevealCardResponse(String pan, String cvv, int expiryMonth, int expiryYear) {

    public static RevealCardResponse from(RevealedCard revealed) {
        return new RevealCardResponse(
                revealed.pan(), revealed.cvv(), revealed.expiryMonth(), revealed.expiryYear());
    }
}
