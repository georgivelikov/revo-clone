package com.revolutclone.simulator.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.revolutclone.messaging.api.EventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class ChaosPoisonPublisherTest {

    @Test
    void publishesAPayloadThatIsNotValidJson() {
        EventPublisher eventPublisher = mock(EventPublisher.class);
        ChaosPoisonPublisher publisher = new ChaosPoisonPublisher(eventPublisher);

        publisher.poison("payment.event.v1");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventPublisher).publish(eq("payment.event.v1"), anyString(), payloadCaptor.capture());

        String payload = payloadCaptor.getValue();
        JsonMapper jsonMapper = JsonMapper.builder().build();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jsonMapper.readTree(payload)))
                .as("a poison payload must fail exactly the same parse a real consumer performs")
                .isNotNull();
    }
}
