package com.revolutclone.fx.chaos;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * plans/phase-07-resilience-and-scale.md's Chaos control plane: the {@code fx.down} lever.
 * {@code @Profile("chaos")}, off by default - see {@link ChaosFxState}'s javadoc. Unauthenticated
 * for the same reason {@code ledger-service}'s {@code ChaosLedgerAdminController} is - see that
 * class's javadoc.
 */
@RestController
@RequestMapping("/admin/chaos/fx")
@Profile("chaos")
public class ChaosFxAdminController {

    private final ChaosFxState state;

    public ChaosFxAdminController(ChaosFxState state) {
        this.state = state;
    }

    @GetMapping
    public ChaosFxCommand get() {
        return new ChaosFxCommand(state.isDown());
    }

    @PostMapping
    public ChaosFxCommand set(@RequestBody ChaosFxCommand command) {
        state.setDown(command.down());
        return new ChaosFxCommand(state.isDown());
    }
}
