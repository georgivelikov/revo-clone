package com.revolutclone.messaging.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.messaging.api.RetryClassification;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The standard shape from plans/phase-07-resilience-and-scale.md, as pure string/duration logic. */
class RetryTopicNamesTest {

    @Test
    void transientFailureOnMainTopicEscalatesToFirstRetryLevel() {
        assertThat(RetryTopicNames.nextTopic("payment.event.v1", RetryClassification.TRANSIENT))
                .isEqualTo("payment.event.v1.retry-5s");
    }

    @Test
    void transientFailureEscalatesOneLevelAtATime() {
        assertThat(RetryTopicNames.nextTopic("payment.event.v1.retry-5s", RetryClassification.TRANSIENT))
                .isEqualTo("payment.event.v1.retry-1m");
        assertThat(RetryTopicNames.nextTopic("payment.event.v1.retry-1m", RetryClassification.TRANSIENT))
                .isEqualTo("payment.event.v1.retry-10m");
    }

    @Test
    void transientFailureOnLastRetryLevelTerminatesAtTheDlt() {
        assertThat(RetryTopicNames.nextTopic("payment.event.v1.retry-10m", RetryClassification.TRANSIENT))
                .isEqualTo("payment.event.v1.DLT");
    }

    @Test
    void fatalFailureGoesStraightToTheDltFromAnyLevel() {
        assertThat(RetryTopicNames.nextTopic("payment.event.v1", RetryClassification.FATAL))
                .isEqualTo("payment.event.v1.DLT");
        assertThat(RetryTopicNames.nextTopic("payment.event.v1.retry-5s", RetryClassification.FATAL))
                .isEqualTo("payment.event.v1.DLT");
    }

    @Test
    void aRecordAlreadyOnTheDltStaysThereRegardlessOfClassification() {
        assertThat(RetryTopicNames.nextTopic("payment.event.v1.DLT", RetryClassification.TRANSIENT))
                .isEqualTo("payment.event.v1.DLT");
    }

    @Test
    void mainTopicOfStripsAnyChainSuffix() {
        assertThat(RetryTopicNames.mainTopicOf("payment.event.v1")).isEqualTo("payment.event.v1");
        assertThat(RetryTopicNames.mainTopicOf("payment.event.v1.retry-5s")).isEqualTo("payment.event.v1");
        assertThat(RetryTopicNames.mainTopicOf("payment.event.v1.retry-1m")).isEqualTo("payment.event.v1");
        assertThat(RetryTopicNames.mainTopicOf("payment.event.v1.retry-10m")).isEqualTo("payment.event.v1");
        assertThat(RetryTopicNames.mainTopicOf("payment.event.v1.DLT")).isEqualTo("payment.event.v1");
    }

    @Test
    void delayOfMatchesTheDesignTable() {
        assertThat(RetryTopicNames.delayOf("ledger.entry.v1.retry-5s")).isEqualTo(Duration.ofSeconds(5));
        assertThat(RetryTopicNames.delayOf("ledger.entry.v1.retry-1m")).isEqualTo(Duration.ofMinutes(1));
        assertThat(RetryTopicNames.delayOf("ledger.entry.v1.retry-10m")).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void delayOfRejectsAMainOrDltTopic() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> RetryTopicNames.delayOf("ledger.entry.v1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
