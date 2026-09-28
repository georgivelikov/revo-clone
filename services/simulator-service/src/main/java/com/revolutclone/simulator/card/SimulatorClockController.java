package com.revolutclone.simulator.card;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * plans/phase-05-cards.md's "controllable clock": "an endpoint that advances a simulated day,
 * rather than waiting 24 hours." See {@link SimulatedClock} and {@code CardSpendService#advanceDay}
 * for what actually moves.
 */
@RestController
@RequestMapping("/simulator/clock")
public class SimulatorClockController {

    private final CardSpendService spendService;

    public SimulatorClockController(CardSpendService spendService) {
        this.spendService = spendService;
    }

    @PostMapping("/advance-day")
    public ResponseEntity<AdvanceDayResult> advanceDay() {
        return ResponseEntity.ok(spendService.advanceDay());
    }
}
