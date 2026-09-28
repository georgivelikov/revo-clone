package com.revolutclone.card.consumer;

import com.revolutclone.card.settlement.SettlementOutcome;
import com.revolutclone.card.settlement.SettlementService;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.SettlementRequestedV1;
import com.revolutclone.messaging.api.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The business half of the settlement consumer: unwraps the envelope, settles, and says what happened.
 * Kafka binding lives in {@link CardAuthorizationKafkaListener}, so this is testable with no broker at
 * all - which is how all four of this task's tests drive it.
 */
@Component
public class SettlementRequestedEventHandler implements EventHandler<SettlementRequestedV1> {

    private static final Logger log = LoggerFactory.getLogger(SettlementRequestedEventHandler.class);

    private final SettlementService settlements;

    public SettlementRequestedEventHandler(SettlementService settlements) {
        this.settlements = settlements;
    }

    @Override
    public void handle(EventEnvelope<SettlementRequestedV1> envelope) {
        SettlementRequestedV1 request = envelope.payload();
        SettlementOutcome outcome = settlements.settle(request);
        log.info("settlement of {} {} on {}: {}", request.settledAmountMinor(), request.currency(),
                request.networkRef(), outcome.describe());
    }
}
