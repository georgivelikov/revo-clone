package com.revolutclone.simulator.fx;

import com.revolutclone.contracts.events.FxRateTickV1;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Owns the one piece of mutable state in this service: the current mid per pair, held as a
 * decimal string rather than a parsed number - see {@link RandomWalkRateGenerator} and CLAUDE.md
 * hard rule 1. Each call to {@link #tick()} walks every supported pair one step and returns the
 * new ticks to publish - kept separate from the actual Kafka send
 * ({@link FxRateTickPublisher}) so the walk itself is unit-testable with no broker.
 */
@Component
public class FxRateTickService {

    static final String SOURCE = "simulator-service";

    private final Map<String, String> currentMid = new ConcurrentHashMap<>(FxPairs.SEED_MID);
    private final RandomWalkRateGenerator generator;
    private final Clock clock;

    @Autowired
    public FxRateTickService(FxSimulatorProperties properties, Clock clock) {
        this(new RandomWalkRateGenerator(new Random(), properties.volatility()), clock);
    }

    FxRateTickService(RandomWalkRateGenerator generator, Clock clock) {
        this.generator = generator;
        this.clock = clock;
    }

    public List<FxRateTickV1> tick() {
        Instant now = clock.instant();
        List<FxRateTickV1> ticks = new ArrayList<>(FxPairs.SEED_MID.size());
        for (String pair : FxPairs.SEED_MID.keySet()) {
            String next = generator.next(currentMid.get(pair));
            currentMid.put(pair, next);
            ticks.add(new FxRateTickV1(pair, next, now, SOURCE));
        }
        return ticks;
    }
}
