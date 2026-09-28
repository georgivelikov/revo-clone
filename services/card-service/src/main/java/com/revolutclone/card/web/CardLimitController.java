package com.revolutclone.card.web;

import com.revolutclone.card.domain.CardLimit;
import com.revolutclone.card.domain.LimitPeriod;
import com.revolutclone.card.service.CardLimitService;
import com.revolutclone.security.api.CurrentUser;
import com.revolutclone.security.api.UserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * plans/phase-05-cards.md task 9's limits endpoints. {@code CardLimit}'s own javadoc: "absence
 * means absence" - a card with no row for a period has no ceiling for it, which is why removing
 * a limit is a real operation here and not merely setting it to some large number.
 */
@RestController
@RequestMapping("/api/cards/{cardId}/limits")
public class CardLimitController {

    private final CardLimitService limitService;

    public CardLimitController(CardLimitService limitService) {
        this.limitService = limitService;
    }

    @GetMapping
    public ResponseEntity<List<CardLimitResponse>> list(
            @CurrentUser UserPrincipal principal, @PathVariable UUID cardId) {

        List<CardLimit> limits = limitService.list(cardId, UUID.fromString(principal.userId()));
        return ResponseEntity.ok(limits.stream().map(CardLimitResponse::from).toList());
    }

    @PutMapping("/{period}")
    public ResponseEntity<CardLimitResponse> upsert(
            @CurrentUser UserPrincipal principal,
            @PathVariable UUID cardId,
            @PathVariable LimitPeriod period,
            @Valid @RequestBody UpsertCardLimitRequest request) {

        CardLimit limit = limitService.upsert(
                cardId, UUID.fromString(principal.userId()), period, request.amountMinor());
        return ResponseEntity.ok(CardLimitResponse.from(limit));
    }

    @DeleteMapping("/{period}")
    public ResponseEntity<Void> remove(
            @CurrentUser UserPrincipal principal, @PathVariable UUID cardId, @PathVariable LimitPeriod period) {

        limitService.remove(cardId, UUID.fromString(principal.userId()), period);
        return ResponseEntity.noContent().build();
    }
}
