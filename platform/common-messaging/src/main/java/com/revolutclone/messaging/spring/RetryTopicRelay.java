package com.revolutclone.messaging.spring;

import com.revolutclone.messaging.api.EventPublisher;
import com.revolutclone.messaging.api.TransientMessagingException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * The out-of-process wait behind the retry-topic chain (see {@link KafkaErrorHandlingConfig}):
 * once {@link KafkaErrorHandlingConfig#kafkaErrorHandler} has escalated a failing record onto
 * {@code <topic>.retry-5s}, {@code .retry-1m} or {@code .retry-10m}, this is what eventually
 * republishes it to the main topic for another attempt, after sleeping for that level's delay.
 *
 * <p>The delay is measured from when this listener receives the record, not from when it was
 * published to the retry topic - simplest to implement correctly, and never shorter than the
 * configured delay (only occasionally longer, under consumer lag), which is the safe direction
 * to be wrong in for a demo-scale system. Sleeping inside the listener thread only blocks this
 * retry topic's own consumer (3 partitions, low volume by construction) - never the main topic,
 * which is the whole point of moving the wait off the hot path.
 *
 * <p>Containers are built programmatically, one per retry level, from {@link RetryTopicProperties}
 * - deliberately not a declarative {@code @KafkaListener}. A service with no
 * {@code revolut.messaging.retry.topics} configured must not open a single Kafka connection for
 * this relay: a container always tries to reach the broker as soon as it starts, even to
 * subscribe to a pattern matching nothing, which would make every test that boots this module's
 * Spring context depend on a live broker. Building zero containers when the topic list is empty
 * is the only way to make "not configured" mean "does nothing" rather than "fails to connect".
 */
@Component
public class RetryTopicRelay implements SmartLifecycle {

    private final ConsumerFactory<Object, Object> consumerFactory;
    private final EventPublisher eventPublisher;
    private final RetryTopicProperties properties;
    private final String groupId;

    private final List<ConcurrentMessageListenerContainer<Object, Object>> containers = new ArrayList<>();
    private volatile boolean running;

    public RetryTopicRelay(ConsumerFactory<Object, Object> consumerFactory, EventPublisher eventPublisher,
            RetryTopicProperties properties, @Value("${spring.application.name:common-messaging}") String applicationName) {
        this.consumerFactory = consumerFactory;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.groupId = applicationName + "-retry-relay";
    }

    @Override
    public void start() {
        for (String suffix : RetryTopicNames.retrySuffixes()) {
            String[] retryTopics = properties.topics().stream().map(topic -> topic + suffix).toArray(String[]::new);
            if (retryTopics.length == 0) {
                continue;
            }
            ContainerProperties containerProperties = new ContainerProperties(retryTopics);
            containerProperties.setGroupId(groupId);
            containerProperties.setAckMode(ContainerProperties.AckMode.MANUAL);
            containerProperties.setMessageListener((AcknowledgingMessageListener<Object, Object>) this::onMessage);
            ConcurrentMessageListenerContainer<Object, Object> container =
                    new ConcurrentMessageListenerContainer<>(consumerFactory, containerProperties);
            container.start();
            containers.add(container);
        }
        running = true;
    }

    private void onMessage(ConsumerRecord<Object, Object> record, Acknowledgment acknowledgment) {
        sleep(RetryTopicNames.delayOf(record.topic()));
        String mainTopic = RetryTopicNames.mainTopicOf(record.topic());
        String key = record.key() == null ? null : record.key().toString();
        eventPublisher.publish(mainTopic, key, (String) record.value());
        acknowledgment.acknowledge();
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Not acknowledged: on shutdown, the record is redelivered and retried again on
            // restart rather than silently dropped.
            throw new TransientMessagingException("interrupted while waiting to retry", e);
        }
    }

    @Override
    public void stop() {
        containers.forEach(ConcurrentMessageListenerContainer::stop);
        containers.clear();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
