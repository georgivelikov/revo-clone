package com.revolutclone.card.web.error;

import com.revolutclone.web.problem.ApiException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * A freeze or unfreeze was asked for on a card that is {@code CANCELLED} - a state no toggle can
 * move it out of. 409, not 404: the card exists and belongs to this holder, it is simply not a
 * card that can be frozen or unfrozen any more. See {@code DeclineReason}'s javadoc on why a
 * cancelled card is a different fact from a frozen one.
 */
public class CardCancelledException extends ApiException {

    private static final URI TYPE = URI.create("https://revolutclone.com/problems/card-cancelled");

    public CardCancelledException(UUID cardId) {
        super(HttpStatus.CONFLICT, TYPE, "Card cancelled", "Card " + cardId + " is cancelled.");
    }
}
