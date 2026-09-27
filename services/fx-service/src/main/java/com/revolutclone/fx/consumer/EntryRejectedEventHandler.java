package com.revolutclone.fx.consumer;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.EntryRejectedV1;
import com.revolutclone.fx.quote.QuoteExecutionService;
import com.revolutclone.messaging.api.EventHandler;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The trigger for plans/phase-04-fx-multicurrency.md's first compensation row: the ledger refused
 * to capture a quote's reservation, so the quote this service already marked {@code CONSUMED} has
 * to be unwound.
 *
 * <p>{@code ledger.entry.v1} carries every service's rejections, and this one recognises its own
 * by the ledger key both sides derive from the quote id - {@code fxquote:<quoteId>}. Anything
 * else on the topic is somebody else's business and is ignored without comment; a rejection whose
 * key looks like ours but does not parse is logged and dropped rather than thrown, because a
 * message this consumer can never make sense of must not be redelivered forever.
 *
 * <p>The compensation itself is {@link QuoteExecutionService#compensate}, which is idempotent on
 * the quote's own state - necessary here, because the listener this runs under replays the topic
 * from the beginning on every startup for the sake of its other handler.
 */
@Component
public class EntryRejectedEventHandler implements EventHandler<EntryRejectedV1> {

    private static final Logger log = LoggerFactory.getLogger(EntryRejectedEventHandler.class);

    /** Mirrors ledger-service's own derivation - see {@code FxExecutionRequestedEventHandler}. */
    private static final String FX_QUOTE_KEY_PREFIX = "fxquote:";

    private final QuoteExecutionService execution;

    public EntryRejectedEventHandler(QuoteExecutionService execution) {
        this.execution = execution;
    }

    @Override
    public void handle(EventEnvelope<EntryRejectedV1> envelope) {
        EntryRejectedV1 event = envelope.payload();
        String key = event.idempotencyKey();
        if (key == null || !key.startsWith(FX_QUOTE_KEY_PREFIX)) {
            return;
        }
        Optional<UUID> quoteId = quoteIdIn(key);
        if (quoteId.isEmpty()) {
            log.warn("ignoring a rejection with an fx key this service cannot parse: {}", key);
            return;
        }
        execution.compensate(quoteId.get(), event.reason());
    }

    private static Optional<UUID> quoteIdIn(String idempotencyKey) {
        try {
            return Optional.of(UUID.fromString(idempotencyKey.substring(FX_QUOTE_KEY_PREFIX.length())));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
