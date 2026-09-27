package com.revolutclone.simulator.fx;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.FxRateTickV1;
import com.revolutclone.messaging.api.EventPublisher;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Goes through {@code common-messaging}'s {@link EventPublisher} port rather than calling the
 * Kafka client's send method directly - see this module's pom.xml and
 * {@code SimulatorServiceApplication} for why this service can use that port directly without
 * the outbox in front of it. Keyed by {@code pair} per CLAUDE.md hard rule 5, so two ticks for
 * the same pair are never reordered by the partitioner.
 */
@Component
public class FxRateTickPublisher {

    static final String TOPIC = "fx.rate.tick.v1";
    private static final String TYPE = "FxRateTickV1";
    private static final int VERSION = 1;

    private final EventPublisher eventPublisher;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public FxRateTickPublisher(EventPublisher eventPublisher, JsonMapper jsonMapper, Clock clock) {
        this.eventPublisher = eventPublisher;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    public void publish(FxRateTickV1 tick) {
        EventEnvelope<FxRateTickV1> envelope = new EventEnvelope<>(
                UUID.randomUUID(), clock.instant(), UUID.randomUUID().toString(), TYPE, VERSION, tick);
        String json = jsonMapper.writeValueAsString(envelope);
        eventPublisher.publish(TOPIC, tick.pair(), json);
    }
}
