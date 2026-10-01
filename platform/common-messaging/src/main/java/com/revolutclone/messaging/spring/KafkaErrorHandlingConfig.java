package com.revolutclone.messaging.spring;

import com.revolutclone.messaging.api.ErrorClassifier;
import com.revolutclone.messaging.api.RetryClassification;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * The retry-topic chain and DLT from
 * plans/phase-07-resilience-and-scale.md §Retry topics and the dead-letter queue:
 *
 * <pre>
 * main topic → handler throws
 *   → retriable?   → &lt;topic&gt;.retry-5s   → .retry-1m   → .retry-10m  → &lt;topic&gt;.DLT
 *   → fatal?       → straight to &lt;topic&gt;.DLT, no retry
 * </pre>
 *
 * <p>Blanket in-memory retry is deliberately not used here - retrying a failing message in place
 * blocks its partition and stalls every other key behind it, which is exactly the failure mode
 * this design avoids. {@link FixedBackOff#FixedBackOff(long, long) new FixedBackOff(0, 0)} means
 * a single attempt: on any failure, {@link DeadLetterPublishingRecoverer} runs immediately and
 * republishes to wherever {@link RetryTopicNames#nextTopic} says the message goes next. Waiting
 * happens out-of-process, on the retry topic itself - see {@code RetryTopicRelay}, which sleeps
 * for that topic's delay before republishing to the main topic for another attempt.
 *
 * <p>Deserialization failures skip the chain and go straight to the DLT - one poison message
 * must never block a partition forever. That needs the consumer's value deserializer wrapped in
 * Spring Kafka's {@code ErrorHandlingDeserializer}, which turns a raw deserialization exception
 * into a {@code DeserializationException} that
 * {@link com.revolutclone.messaging.spring.DefaultErrorClassifier DefaultErrorClassifier}
 * classifies as fatal, same as everything else here - {@code DeadLetterPublishingRecoverer}
 * above then sends it straight to the DLT via the same {@code kafkaErrorHandler}, before a
 * listener method is ever invoked. This is per-service {@code spring.kafka.consumer.*}
 * configuration, not something this shared bean can set: each consuming service's
 * {@code application.yml} sets {@code spring.kafka.consumer.value-deserializer} to
 * {@code ErrorHandlingDeserializer} and delegates to the real deserializer (this project's
 * consumers all take the raw event envelope as {@code String}, so the delegate is
 * {@code StringDeserializer}) via the {@code spring.deserializer.value.delegate.class} property.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaErrorHandlingConfig {

    @Bean
    public ErrorClassifier errorClassifier() {
        return new DefaultErrorClassifier();
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafkaOperations,
            ErrorClassifier errorClassifier, ObjectProvider<MeterRegistry> meterRegistryProvider) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaOperations,
                (record, exception) -> {
                    RetryClassification classification = errorClassifier.classify(exception);
                    String nextTopic = RetryTopicNames.nextTopic(record.topic(), classification);
                    // plans/phase-08-load-and-observability.md task 3's kafka.dlt.messages{topic}
                    // counter - incremented only on the hop that actually lands on the DLT, not
                    // every escalation up the retry chain. Complements kafka.dlt.depth
                    // (DltDepthMetrics): depth can fall after a replay, this never does.
                    if (nextTopic.endsWith(RetryTopicNames.DLT_SUFFIX)) {
                        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
                        if (meterRegistry != null) {
                            meterRegistry.counter("kafka.dlt.messages", "topic", RetryTopicNames.mainTopicOf(nextTopic))
                                    .increment();
                        }
                    }
                    // Retry/DLT topics have 3 partitions, the main topics 12 - a negative
                    // partition tells the recoverer to let the producer's partitioner pick one by
                    // key instead of reusing the original (possibly out-of-range) partition
                    // number, per plans/phase-00-foundations.md's "3 partitions, not 12".
                    return new TopicPartition(nextTopic, -1);
                });

        return new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 0L));
    }
}
