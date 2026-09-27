package com.revolutclone.fx.consumer;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.FxExecutionRequestedV1;
import com.revolutclone.fx.quote.FxQuoteRepository;
import com.revolutclone.messaging.api.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code fx_quote.state} correct when something other than this service's own
 * {@code QuoteExecutionService.execute} is what actually executed the quote -
 * plans/phase-04-fx-multicurrency.md task 8's cross-currency transfer, where payment-service
 * obtains a quote from this service but then appends {@link FxExecutionRequestedV1} to its
 * <em>own</em> outbox rather than calling back into fx-service to execute it: the money-moving
 * half of that saga is "the same saga" payment-service already runs for a same-currency transfer
 * - one local transaction, no second sideways HTTP call back to fx-service.
 *
 * <p>Consuming fx-service's own outbound topic is unusual, and would be redundant for the
 * self-service exchange path (task 04.5), which already performs this exact {@code ACTIVE ->
 * CONSUMED} transition synchronously, in-process, before publishing - by the time this handler
 * sees that same event replayed back off Kafka, the quote is already {@code CONSUMED} and
 * {@link FxQuoteRepository#markConsumed} is a harmless no-op. It is not redundant for a quote
 * this service never executed itself, which is the only case where this call actually changes
 * anything - without it, the quote would sit {@code ACTIVE} until {@code QuoteExpirySweep}
 * incorrectly marked it {@code EXPIRED} despite having actually been consumed.
 *
 * <p>Naturally idempotent - a redelivered event just repeats a conditional update that has
 * already happened - so this is registered without the {@code processed_event} wrapper, the same
 * reasoning {@code LedgerAccountCreatedEventHandler} gives for its own registration.
 */
@Component
public class QuoteConsumptionSyncHandler implements EventHandler<FxExecutionRequestedV1> {

    private static final Logger log = LoggerFactory.getLogger(QuoteConsumptionSyncHandler.class);

    private final FxQuoteRepository quotes;

    public QuoteConsumptionSyncHandler(FxQuoteRepository quotes) {
        this.quotes = quotes;
    }

    @Override
    public void handle(EventEnvelope<FxExecutionRequestedV1> envelope) {
        FxExecutionRequestedV1 event = envelope.payload();
        if (quotes.markConsumed(event.quoteId(), envelope.occurredAt()) == 1) {
            log.info("quote {} marked CONSUMED by an execution this service did not itself "
                    + "request - a cross-currency transfer", event.quoteId());
        }
    }
}
