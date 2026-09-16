package com.revolutclone.messaging.idempotent;

import com.revolutclone.contracts.DomainEvent;
import com.revolutclone.messaging.api.EventHandler;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/** Builds {@link IdempotentHandler} wrappers with this module's shared repository/clock/tx wiring. */
@Component
public class IdempotentHandlerFactory {

    private final ProcessedEventRepository repository;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;

    public IdempotentHandlerFactory(ProcessedEventRepository repository, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.clock = clock;
        this.transactionManager = transactionManager;
    }

    public <T extends DomainEvent> IdempotentHandler<T> wrap(EventHandler<T> delegate, String consumerGroup) {
        return new IdempotentHandler<>(delegate, repository, consumerGroup, clock, transactionManager);
    }
}
