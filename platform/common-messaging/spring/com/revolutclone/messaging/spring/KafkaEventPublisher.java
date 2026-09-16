package com.revolutclone.messaging.spring;

import com.revolutclone.messaging.api.EventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * The only implementation of {@link EventPublisher} in the project, and (with
 * {@link KafkaEventHandlerAdapter}) one of the only two places allowed to speak Kafka directly.
 * The outbox already holds a fully-serialized {@code EventEnvelope}, so this is a straight
 * relay - no re-serialization, no business logic.
 *
 * <p>Lives under this module's separate {@code spring/} source root (see {@code pom.xml}'s
 * {@code build-helper-maven-plugin} config), not the default {@code src/main/java}, so its
 * on-disk path literally matches {@code common-messaging/spring/} - the boundary
 * plans/phase-00-foundations.md's deliverables tree draws and the one the build's tripwire
 * enforces for where a direct {@code KafkaTemplate.send} call is allowed to live.
 */
@Component
public class KafkaEventPublisher implements EventPublisher {

    // Boot's default auto-configured bean is KafkaTemplate<Object, Object>; matching that type
    // exactly is what makes this injectable without a service having to define its own
    // KafkaTemplate bean just to narrow the generics to <String, String>.
    private final KafkaTemplate<Object, Object> kafkaTemplate;

    public KafkaEventPublisher(KafkaTemplate<Object, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(String topic, String partitionKey, String payloadJson) {
        kafkaTemplate.send(topic, partitionKey, payloadJson);
    }
}
