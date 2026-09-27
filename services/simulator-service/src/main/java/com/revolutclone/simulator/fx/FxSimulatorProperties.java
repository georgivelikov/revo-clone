package com.revolutclone.simulator.fx;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code volatility} is the "configurable volatility" plans/phase-04-fx-multicurrency.md's task
 * list calls for: the per-tick Gaussian standard deviation, expressed as a fraction of the
 * current mid so the same knob produces pip-sized steps on EUR/GBP and yen-sized steps on
 * EUR/JPY. A decimal string, not a parsed arbitrary-precision decimal field - see
 * {@link RandomWalkRateGenerator} and CLAUDE.md hard rule 1.
 */
@ConfigurationProperties(prefix = "revolut.simulator.fx")
public record FxSimulatorProperties(@DefaultValue("0.0005") String volatility, @DefaultValue("500") long tickIntervalMs) {
}
