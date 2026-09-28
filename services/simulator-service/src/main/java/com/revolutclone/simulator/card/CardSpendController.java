package com.revolutclone.simulator.card;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * plans/phase-05-cards.md's Merchant simulator endpoints, minus the clock (see
 * {@code SimulatorClockController}). Every response is 202: each of these ends in a Kafka publish
 * to card-service, never a synchronous decision - the caller finds out whether a spend was
 * approved the same way the real app does, from the push (or the poll) that follows.
 */
@RestController
@RequestMapping("/simulator/cards")
public class CardSpendController {

    private static final int DEFAULT_BATCH_COUNT = 20;
    private static final int MAX_BATCH_COUNT = 500;

    private final CardSpendService spendService;

    public CardSpendController(CardSpendService spendService) {
        this.spendService = spendService;
    }

    @PostMapping("/spend")
    public ResponseEntity<SpendResult> spend(@Valid @RequestBody SpendRequest request) {
        SpendResult result = spendService.spend(
                new SpendCommand(request.cardId(), request.merchant(), request.amountMinor(), request.currency()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    @PostMapping("/spend/batch")
    public ResponseEntity<List<SpendResult>> spendBatch(
            @Valid @RequestBody SpendRequest request,
            @RequestParam(name = "count", defaultValue = "" + DEFAULT_BATCH_COUNT) int count) {

        int bounded = Math.clamp(count, 1, MAX_BATCH_COUNT);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(spendService.spendBatch(request.cardId(), bounded));
    }

    @PostMapping("/reverse/{networkRef}")
    public ResponseEntity<Void> reverse(@PathVariable String networkRef) {
        spendService.reverse(networkRef);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
