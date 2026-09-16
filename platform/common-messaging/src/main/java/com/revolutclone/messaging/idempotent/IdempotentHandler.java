package com.revolutclone.messaging.idempotent;

import com.revolutclone.contracts.DomainEvent;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.messaging.api.EventHandler;
import java.time.Clock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wraps a service's {@link EventHandler} so the same event, delivered twice, produces exactly
 * one side effect. The dedup insert runs in its own {@code REQUIRES_NEW} transaction - a
 * duplicate-key violation only rolls back that one-row insert, never the delegate's work, and
 * is treated as "already done, ack and move on" rather than an error.
 *
 * <p>Built via {@link IdempotentHandlerFactory#wrap} rather than autowired directly, since the
 * consumer group and delegate differ per listener.
 */
public class IdempotentHandler<T extends DomainEvent> implements EventHandler<T> {

    private final EventHandler<T> delegate;
    private final ProcessedEventRepository repository;
    private final String consumerGroup;
    private final Clock clock;
    private final TransactionTemplate requiresNew;

    IdempotentHandler(EventHandler<T> delegate, ProcessedEventRepository repository, String consumerGroup,
            Clock clock, PlatformTransactionManager transactionManager) {
        this.delegate = delegate;
        this.repository = repository;
        this.consumerGroup = consumerGroup;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void handle(EventEnvelope<T> envelope) {
        if (!tryMarkProcessed(envelope)) {
            return;
        }
        delegate.handle(envelope);
    }

    private boolean tryMarkProcessed(EventEnvelope<T> envelope) {
        try {
            requiresNew.executeWithoutResult(status -> repository
                    .saveAndFlush(new ProcessedEvent(consumerGroup, envelope.eventId(), clock.instant())));
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            return false;
        }
    }
}
