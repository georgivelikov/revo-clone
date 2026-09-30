package com.revolutclone.ledger.chaos;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * plans/phase-07-resilience-and-scale.md's Chaos control plane: {@code ledger.delay},
 * {@code ledger.fail-rate} and {@code ledger.reject-all}. {@code @Profile("chaos")}, off by
 * default - see {@link ChaosLedgerState}'s javadoc.
 *
 * <p>Unauthenticated (see {@code SecurityConfig}'s {@code /admin/chaos/**} carve-out) for the same
 * reason {@code card-service}'s {@code /internal/cards/*&#47;pan-for-network} is:
 * {@code simulator-service}, the caller, has no service-to-service identity yet - a Phase 07
 * concern this project has not built. The profile gate is what keeps that acceptable.
 */
@RestController
@RequestMapping("/admin/chaos/ledger")
@Profile("chaos")
public class ChaosLedgerAdminController {

    private final ChaosLedgerState state;

    public ChaosLedgerAdminController(ChaosLedgerState state) {
        this.state = state;
    }

    @GetMapping
    public ChaosLedgerStateResponse get() {
        return ChaosLedgerStateResponse.from(state);
    }

    @PostMapping
    public ChaosLedgerStateResponse set(@RequestBody ChaosLedgerCommand command) {
        if (command.delayMs() != null) {
            state.setDelayMs(command.delayMs());
        }
        if (command.failRatePercent() != null) {
            state.setFailRatePercent(command.failRatePercent());
        }
        if (command.rejectAll() != null) {
            state.setRejectAll(command.rejectAll());
        }
        return ChaosLedgerStateResponse.from(state);
    }

    @PostMapping("/reset")
    public ChaosLedgerStateResponse reset() {
        state.reset();
        return ChaosLedgerStateResponse.from(state);
    }
}
