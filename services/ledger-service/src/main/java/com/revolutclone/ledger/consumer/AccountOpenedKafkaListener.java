package com.revolutclone.ledger.consumer;

import com.revolutclone.contracts.events.AccountOpenedV1;
import com.revolutclone.messaging.idempotent.IdempotentHandler;
import com.revolutclone.messaging.idempotent.IdempotentHandlerFactory;
import com.revolutclone.messaging.spring.KafkaEventHandlerAdapter;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The thin {@code @KafkaListener} common-messaging's design calls for - mirrors
 * account-service's {@code UserRegisteredKafkaListener} exactly. This is the only place in
 * ledger-service that imports a Kafka type for this consumer; the business logic lives in
 * {@link AccountOpenedEventHandler} and is unit-testable with no broker at all.
 *
 * <p>{@code groupId=ledger-service} is this consumer's identity in the {@code processed_event}
 * table's {@code consumer_group} column - a redelivered {@code AccountOpenedV1} is a no-op here.
 */
@Component
public class AccountOpenedKafkaListener {

    private static final String CONSUMER_GROUP = "ledger-service";

    private final KafkaEventHandlerAdapter<AccountOpenedV1> adapter;

    public AccountOpenedKafkaListener(AccountOpenedEventHandler delegate, IdempotentHandlerFactory factory,
            JsonMapper jsonMapper) {
        IdempotentHandler<AccountOpenedV1> idempotent = factory.wrap(delegate, CONSUMER_GROUP);
        this.adapter = new KafkaEventHandlerAdapter<>(idempotent, jsonMapper, AccountOpenedV1.class);
    }

    @KafkaListener(topics = "account.account.v1", groupId = CONSUMER_GROUP)
    public void onMessage(String rawJson) {
        adapter.onMessage(rawJson);
    }
}
