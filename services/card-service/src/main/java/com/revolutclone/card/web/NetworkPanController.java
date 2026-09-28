package com.revolutclone.card.web;

import com.revolutclone.card.service.NetworkPanLookupService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task 05.8: {@code simulator-service} plays the card network, and a real network already knows a
 * card's PAN from the physical swipe - it never calls an issuer to learn one. This endpoint hands
 * back what that network would already have, so the simulator (which owns no database and cannot
 * read card-service's - CLAUDE.md hard rule 7) can build the {@code AuthorizationRequestedV1} its
 * spend endpoints publish.
 *
 * <p><strong>Deliberately not behind a JWT, unlike every other route this service exposes.</strong>
 * There is no user in this request - the caller is a backend simulator with no login of its own -
 * and this project has no service-to-service client-credentials identity yet (a Phase 07 concern,
 * the same gap {@code LedgerReservationsRestClient} and {@code CardFxRatesRestClient} document on
 * card-service's own outbound calls). Exempting this one path in {@code SecurityConfig} is the
 * pragmatic choice for a project that is explicit about not building real PCI compliance (plans/
 * phase-05-cards.md's Risks section): the alternative is inventing a throwaway service identity
 * now that Phase 07 would immediately replace. Not routed by the gateway, same precedent as every
 * other {@code /internal/**} endpoint in this project.
 */
@RestController
@RequestMapping("/internal/cards")
public class NetworkPanController {

    private final NetworkPanLookupService lookup;

    public NetworkPanController(NetworkPanLookupService lookup) {
        this.lookup = lookup;
    }

    @GetMapping("/{cardId}/pan-for-network")
    public ResponseEntity<NetworkPanResponse> panForNetwork(@PathVariable UUID cardId) {
        return ResponseEntity.ok(new NetworkPanResponse(lookup.panFor(cardId)));
    }
}
