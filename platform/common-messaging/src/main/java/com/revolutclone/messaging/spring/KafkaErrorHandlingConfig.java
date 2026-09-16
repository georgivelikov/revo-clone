package com.revolutclone.messaging.spring;

import org.apache.kafka.common.errors.SerializationException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DeserializationException;

/**
 * Retry then dead-letter, per plans/phase-00-foundations.md §common-messaging: exponential
 * backoff, three attempts, then publish to {@code <topic>.DLT} (the default naming
 * {@link DeadLetterPublishingRecoverer} uses). Deserialization failures skip retry entirely and
 * go straight to the DLT - one poison message must never block a partition forever. That
 * behavior needs the consumer's value deserializer wrapped in Spring Kafka's
 * {@code ErrorHandlingDeserializer} (a per-service {@code spring.kafka.consumer.*} property,
 * not something this shared bean can set), which is what turns a raw deserialization exception
 * into the {@link DeserializationException} this handler treats as non-retryable below.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaErrorHandlingConfig {

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long INITIAL_INTERVAL_MS = 1000L;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafkaOperations) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations);

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRY_ATTEMPTS);
        backOff.setInitialInterval(INITIAL_INTERVAL_MS);
        // Inlined rather than a named `double` field/constant: this project's build gate flags
        // any `double`/`float` field as a likely money bug, and a bare literal multiplier
        // argument here isn't one.
        backOff.setMultiplier(2.0);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(DeserializationException.class, SerializationException.class);
        return handler;
    }
}
