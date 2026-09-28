package com.revolutclone.card.consumer;

import com.revolutclone.card.settlement.SettlementOutcome;
import com.revolutclone.card.settlement.SettlementService;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.ReversalRequestedV1;
import com.revolutclone.messaging.api.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The reversal half of the settlement consumer. A separate handler from
 * {@link SettlementRequestedEventHandler} because it is a separate message type on the same topic, and
 * {@code EventTypeRouter} dispatches by type - not because the two share nothing, which they mostly do
 * (both end up in {@code SettlementService}).
 */
@Component
public class ReversalRequestedEventHandler implements EventHandler<ReversalRequestedV1> {

    private static final Logger log = LoggerFactory.getLogger(ReversalRequestedEventHandler.class);

    private final SettlementService settlements;

    public ReversalRequestedEventHandler(SettlementService settlements) {
        this.settlements = settlements;
    }

    @Override
    public void handle(EventEnvelope<ReversalRequestedV1> envelope) {
        ReversalRequestedV1 request = envelope.payload();
        SettlementOutcome outcome = settlements.reverse(request);
        log.info("reversal of {}: {}", request.networkRef(), outcome.describe());
    }
}
