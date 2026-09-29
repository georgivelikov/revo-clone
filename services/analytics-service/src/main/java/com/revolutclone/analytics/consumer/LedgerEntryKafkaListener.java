package com.revolutclone.analytics.consumer;

import com.revolutclone.contracts.events.EntryPostedV1;
import com.revolutclone.messaging.spring.EventTypeRouter;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code ledger.entry.v1} carries every ledger event there is - postings, rejections, every
 * reservation transition, drift reports - so this routes on the envelope's {@code type} and
 * ignores the rest. Exactly one type matters to analytics-service today: {@link EntryPostedV1},
 * the one event that says money actually moved. A rejection or a reservation hold/capture/
 * release is a state transition with no posting behind it and has nothing for a spend
 * projection to record; {@code LedgerDriftDetectedV1} and {@code LedgerAccountCreatedV1} are
 * ledger-service's own operational concerns, not this service's.
 *
 * <p><strong>Not {@code ConsumerSeekAware}.</strong> Unlike the small reference-data mappings
 * that fx-service and card-service replay from the beginning on every partition assignment
 * (this same topic's {@code LedgerAccountCreatedV1}), this consumer's own table is the running
 * history of every posting ever made - reprocessing all of it on every restart would be
 * genuinely expensive at scale, not merely redundant. Ordinary committed-offset consumption is
 * the right steady state; a full replay from offset zero is a deliberate, separate operation
 * (the phase's {@code .\tasks.ps1 analytics-rebuild}), not something this listener does on its
 * own initiative.
 */
@Component
public class LedgerEntryKafkaListener {

    /** Keyed by the ledger account id - see plans/phase-02-ledger.md's {@code ### Events out}. */
    private static final String TOPIC = "ledger.entry.v1";

    private static final String CONSUMER_GROUP = "analytics-service";

    private final EventTypeRouter router;

    public LedgerEntryKafkaListener(EntryPostedEventHandler entryPosted, JsonMapper jsonMapper) {
        this.router = EventTypeRouter.builder(jsonMapper)
                .register(EntryPostedV1.class, entryPosted)
                .build();
    }

    @KafkaListener(topics = TOPIC, groupId = CONSUMER_GROUP)
    public void onMessage(String rawJson) {
        router.onMessage(rawJson);
    }
}
