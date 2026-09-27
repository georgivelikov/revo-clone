package com.revolutclone.simulator.fx;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires roughly twice a second per plans/phase-04-fx-multicurrency.md's Design detail, so a
 * quote's 30s expiry and the "rate moved between quote and confirm" race are both reachable in
 * testing. The interval is the "configurable volatility" task's other knob, alongside
 * {@link FxSimulatorProperties#volatility()}.
 */
@Component
public class FxRateTickScheduler {

    private final FxRateTickService rateTickService;
    private final FxRateTickPublisher publisher;

    public FxRateTickScheduler(FxRateTickService rateTickService, FxRateTickPublisher publisher) {
        this.rateTickService = rateTickService;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${revolut.simulator.fx.tick-interval-ms:500}")
    public void tick() {
        rateTickService.tick().forEach(publisher::publish);
    }
}
