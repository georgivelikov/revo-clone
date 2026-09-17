package com.revolutclone.account.consumer;

import com.revolutclone.contracts.events.UserRegisteredV1;
import com.revolutclone.messaging.idempotent.IdempotentHandler;
import com.revolutclone.messaging.idempotent.IdempotentHandlerFactory;
import com.revolutclone.messaging.spring.KafkaEventHandlerAdapter;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The thin {@code @KafkaListener} plans/phase-00-foundations.md's common-messaging design
 * calls for: this is the only place in account-service that imports a Kafka type. All the
 * actual deserialize-then-dispatch logic lives in {@link KafkaEventHandlerAdapter}, and the
 * business logic itself is unit-testable via {@link UserRegisteredEventHandler} with no broker
 * at all.
 *
 * <p>{@code groupId=account-service} is also this consumer's identity in the
 * {@code processed_event} table's {@code consumer_group} column - {@link IdempotentHandlerFactory}
 * wraps the delegate with it below, so the same event redelivered to this consumer group is a
 * no-op, while a different service's own consumer group (none yet) would process it
 * independently.
 */
@Component
public class UserRegisteredKafkaListener {

    private static final String CONSUMER_GROUP = "account-service";

    private final KafkaEventHandlerAdapter<UserRegisteredV1> adapter;

    public UserRegisteredKafkaListener(UserRegisteredEventHandler delegate, IdempotentHandlerFactory factory,
            JsonMapper jsonMapper) {
        IdempotentHandler<UserRegisteredV1> idempotent = factory.wrap(delegate, CONSUMER_GROUP);
        this.adapter = new KafkaEventHandlerAdapter<>(idempotent, jsonMapper, UserRegisteredV1.class);
    }

    @KafkaListener(topics = "identity.user.v1", groupId = CONSUMER_GROUP)
    public void onMessage(String rawJson) {
        adapter.onMessage(rawJson);
    }
}
