package com.revolutclone.messaging.outbox;

import com.revolutclone.contracts.DomainEvent;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.messaging.api.OutboxAppender;
import java.time.Clock;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wraps the caller's {@link DomainEvent} in an {@link EventEnvelope}, serializes it, and writes
 * one unpublished {@link OutboxEvent} row - in the caller's own transaction, since this bean
 * carries no {@code @Transactional} boundary of its own beyond propagating into whatever the
 * caller started (REQUIRED is Spring's default).
 *
 * <p>{@code aggregate_type}/{@code aggregate_id} aren't parameters of
 * {@link OutboxAppender#append}, so they're derived: {@code aggregate_type} is the topic (which
 * already names the owning entity by project convention, e.g. {@code account.account.v1}) and
 * {@code aggregate_id} is the partition key (which is always the entity id per CLAUDE.md hard
 * rule 5).
 */
@Component
public class OutboxAppenderImpl implements OutboxAppender {

    private static final Pattern VERSION_SUFFIX = Pattern.compile("V(\\d+)$");
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final OutboxEventRepository repository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public OutboxAppenderImpl(OutboxEventRepository repository, JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void append(String topic, String partitionKey, DomainEvent event) {
        String type = event.getClass().getSimpleName();
        EventEnvelope<DomainEvent> envelope = new EventEnvelope<>(
                UUID.randomUUID(),
                clock.instant(),
                correlationId(),
                type,
                versionOf(type),
                event);

        String payload = jsonMapper.writeValueAsString(envelope);

        OutboxEvent row = new OutboxEvent(
                topic, partitionKey, type, payload, partitionKey, topic, clock.instant());
        repository.save(row);
    }

    private static String correlationId() {
        String fromMdc = MDC.get(CORRELATION_ID_MDC_KEY);
        return fromMdc != null ? fromMdc : UUID.randomUUID().toString();
    }

    private static int versionOf(String eventTypeSimpleName) {
        Matcher matcher = VERSION_SUFFIX.matcher(eventTypeSimpleName);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 1;
    }
}
