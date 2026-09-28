package com.revolutclone.card.web;

import com.revolutclone.card.domain.Card;
import com.revolutclone.card.service.CardIssuanceService;
import com.revolutclone.card.service.CardQueryService;
import com.revolutclone.card.service.CardRevealService;
import com.revolutclone.card.service.CardStateService;
import com.revolutclone.card.service.IssueCardCommand;
import com.revolutclone.card.service.RevealedCard;
import com.revolutclone.security.api.CurrentUser;
import com.revolutclone.security.api.UserPrincipal;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cards")
public class CardController {

    private final CardIssuanceService issuanceService;
    private final CardRevealService revealService;
    private final CardStateService stateService;
    private final CardQueryService queryService;

    public CardController(CardIssuanceService issuanceService, CardRevealService revealService,
            CardStateService stateService, CardQueryService queryService) {
        this.issuanceService = issuanceService;
        this.revealService = revealService;
        this.stateService = stateService;
        this.queryService = queryService;
    }

    /** Task 05.10's Card screen: every card this holder has issued. */
    @GetMapping
    public ResponseEntity<List<CardResponse>> list(@CurrentUser UserPrincipal principal) {
        List<Card> cards = queryService.listCards(UUID.fromString(principal.userId()));
        return ResponseEntity.ok(cards.stream().map(CardResponse::from).toList());
    }

    /** Task 05.10's spend feed - "pending clearly distinct from settled." */
    @GetMapping("/{cardId}/authorizations")
    public ResponseEntity<List<CardAuthorizationResponse>> authorizations(
            @CurrentUser UserPrincipal principal, @PathVariable UUID cardId) {

        var authorizations = queryService.listAuthorizations(cardId, UUID.fromString(principal.userId()));
        return ResponseEntity.ok(authorizations.stream().map(CardAuthorizationResponse::from).toList());
    }

    @PostMapping
    public ResponseEntity<CardResponse> issue(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody IssueCardRequest request) {

        Card card = issuanceService.issue(new IssueCardCommand(
                UUID.fromString(principal.userId()),
                request.accountId(),
                request.type(),
                request.label(),
                request.pin()));

        return ResponseEntity.created(URI.create("/api/cards/" + card.getId()))
                .body(CardResponse.from(card));
    }

    /**
     * The one endpoint in this service that can return a full PAN - see
     * plans/phase-05-cards.md's PAN handling note. The PIN in the request body <em>is</em> the
     * step-up: a valid JWT alone is not enough to see this card's number.
     */
    @PostMapping("/{cardId}/reveal")
    public ResponseEntity<RevealCardResponse> reveal(
            @CurrentUser UserPrincipal principal,
            @PathVariable UUID cardId,
            @Valid @RequestBody RevealCardRequest request) {

        RevealedCard revealed = revealService.reveal(
                cardId, UUID.fromString(principal.userId()), request.pin());

        return ResponseEntity.ok(RevealCardResponse.from(revealed));
    }

    /** plans/phase-05-cards.md task 9: "Freeze toggle." Idempotent - see {@code Card#freeze}. */
    @PostMapping("/{cardId}/freeze")
    public ResponseEntity<CardResponse> freeze(@CurrentUser UserPrincipal principal, @PathVariable UUID cardId) {
        Card card = stateService.freeze(cardId, UUID.fromString(principal.userId()));
        return ResponseEntity.ok(CardResponse.from(card));
    }

    @PostMapping("/{cardId}/unfreeze")
    public ResponseEntity<CardResponse> unfreeze(@CurrentUser UserPrincipal principal, @PathVariable UUID cardId) {
        Card card = stateService.unfreeze(cardId, UUID.fromString(principal.userId()));
        return ResponseEntity.ok(CardResponse.from(card));
    }
}
