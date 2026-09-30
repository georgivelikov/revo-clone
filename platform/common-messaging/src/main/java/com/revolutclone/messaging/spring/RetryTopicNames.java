package com.revolutclone.messaging.spring;

import com.revolutclone.messaging.api.RetryClassification;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The topic-naming half of the standard shape from
 * plans/phase-07-resilience-and-scale.md §Retry topics and the dead-letter queue:
 *
 * <pre>
 * main topic → handler throws
 *   → retriable?   → &lt;topic&gt;.retry-5s   → .retry-1m   → .retry-10m  → &lt;topic&gt;.DLT
 *   → fatal?       → straight to &lt;topic&gt;.DLT, no retry
 * </pre>
 *
 * <p>Pure string/duration logic, no Kafka type - both {@link KafkaErrorHandlingConfig} (deciding
 * where a failing record goes next) and {@code RetryTopicRelay} (deciding how long to wait on a
 * retry topic before trying again) share it, so the two halves of the chain agree on names.
 */
final class RetryTopicNames {

    /** Ordered youngest-to-oldest; also the escalation order. */
    private static final Map<String, Duration> RETRY_DELAYS = new LinkedHashMap<>();

    static {
        RETRY_DELAYS.put(".retry-5s", Duration.ofSeconds(5));
        RETRY_DELAYS.put(".retry-1m", Duration.ofMinutes(1));
        RETRY_DELAYS.put(".retry-10m", Duration.ofMinutes(10));
    }

    static final String DLT_SUFFIX = ".DLT";

    private static final List<String> RETRY_SUFFIXES = List.copyOf(RETRY_DELAYS.keySet());

    private RetryTopicNames() {
    }

    /** The retry-topic suffixes, in escalation order - what a relay needs to subscribe to. */
    static List<String> retrySuffixes() {
        return RETRY_SUFFIXES;
    }

    /** Strips any retry/DLT suffix, returning the original main topic name. */
    static String mainTopicOf(String topic) {
        for (String suffix : RETRY_SUFFIXES) {
            if (topic.endsWith(suffix)) {
                return topic.substring(0, topic.length() - suffix.length());
            }
        }
        if (topic.endsWith(DLT_SUFFIX)) {
            return topic.substring(0, topic.length() - DLT_SUFFIX.length());
        }
        return topic;
    }

    /** How long a message on this retry topic must wait before being tried again. */
    static Duration delayOf(String retryTopic) {
        for (Map.Entry<String, Duration> entry : RETRY_DELAYS.entrySet()) {
            if (retryTopic.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        throw new IllegalArgumentException("not a retry topic: " + retryTopic);
    }

    /**
     * Where a record that failed on {@code currentTopic} goes next, given how its exception was
     * classified. A fatal classification always goes straight to the DLT; a transient one
     * escalates one level, and a transient failure on the last retry level also lands on the DLT
     * - the chain terminates there rather than retrying forever.
     */
    static String nextTopic(String currentTopic, RetryClassification classification) {
        String mainTopic = mainTopicOf(currentTopic);
        if (classification == RetryClassification.FATAL || currentTopic.endsWith(DLT_SUFFIX)) {
            return mainTopic + DLT_SUFFIX;
        }
        // -1 (not found) for the main topic itself, so + 1 correctly picks the first retry level.
        int nextLevel = RETRY_SUFFIXES.indexOf(currentSuffix(currentTopic)) + 1;
        if (nextLevel >= RETRY_SUFFIXES.size()) {
            return mainTopic + DLT_SUFFIX;
        }
        return mainTopic + RETRY_SUFFIXES.get(nextLevel);
    }

    /** Empty when {@code topic} is the main topic itself (no retry suffix). */
    private static String currentSuffix(String topic) {
        for (String suffix : RETRY_SUFFIXES) {
            if (topic.endsWith(suffix)) {
                return suffix;
            }
        }
        return "";
    }
}
