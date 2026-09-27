package com.revolutclone.simulator.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.FxRateTickV1;
import com.revolutclone.messaging.api.EventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class FxRateTickPublisherTest {

    private final EventPublisher eventPublisher = mock();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final FxRateTickPublisher publisher = new FxRateTickPublisher(eventPublisher, jsonMapper, clock);

    @Test
    void publishesToTheRateTickTopicKeyedByPair() {
        FxRateTickV1 tick = new FxRateTickV1("EUR/GBP", "0.86000000", Instant.now(), "simulator-service");

        publisher.publish(tick);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventPublisher).publish(eq("fx.rate.tick.v1"), eq("EUR/GBP"), payloadCaptor.capture());

        EventEnvelope<FxRateTickV1> envelope = jsonMapper.readValue(
                payloadCaptor.getValue(), new TypeReference<EventEnvelope<FxRateTickV1>>() {});

        assertThat(envelope.eventId()).isNotNull();
        assertThat(envelope.correlationId()).isNotBlank();
        assertThat(envelope.type()).isEqualTo("FxRateTickV1");
        assertThat(envelope.version()).isEqualTo(1);
        assertThat(envelope.payload()).isEqualTo(tick);
    }

    @Test
    void twoTicksForTheSamePairGetDifferentEventIdsAndCorrelationIds() {
        FxRateTickV1 tick = new FxRateTickV1("EUR/USD", "1.08000000", Instant.now(), "simulator-service");

        publisher.publish(tick);
        publisher.publish(tick);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventPublisher, times(2)).publish(any(), any(), payloadCaptor.capture());

        List<EventEnvelope<FxRateTickV1>> envelopes = payloadCaptor.getAllValues().stream()
                .map(json -> jsonMapper.readValue(json, new TypeReference<EventEnvelope<FxRateTickV1>>() {}))
                .toList();

        assertThat(envelopes.get(0).eventId()).isNotEqualTo(envelopes.get(1).eventId());
        assertThat(envelopes.get(0).correlationId()).isNotEqualTo(envelopes.get(1).correlationId());
    }
}
