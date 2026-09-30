package com.revolutclone.messaging.spring;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code revolut.messaging.retry.topics} - the main topics a service wants relayed through the
 * {@code .retry-5s} / {@code .retry-1m} / {@code .retry-10m} chain, e.g.:
 *
 * <pre>
 * revolut:
 *   messaging:
 *     retry:
 *       topics: [payment.event.v1]
 * </pre>
 *
 * <p>Empty by default, which is deliberate: a service that has not declared any retry topics
 * gets {@link RetryTopicRelay}'s listener subscribed to a pattern matching nothing rather than
 * failing to start, so adding this module never requires every service to configure it
 * immediately.
 */
@ConfigurationProperties(prefix = "revolut.messaging.retry")
public record RetryTopicProperties(List<String> topics) {

    public RetryTopicProperties {
        topics = topics == null ? List.of() : List.copyOf(topics);
    }
}
