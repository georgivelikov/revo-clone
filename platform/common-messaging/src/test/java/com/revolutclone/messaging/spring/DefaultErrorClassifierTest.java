package com.revolutclone.messaging.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.messaging.api.FatalMessagingException;
import com.revolutclone.messaging.api.RetryClassification;
import com.revolutclone.messaging.api.TransientMessagingException;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.support.serializer.DeserializationException;

/** The classification table from plans/phase-07-resilience-and-scale.md §Retry topics and the DLT. */
class DefaultErrorClassifierTest {

    private final DefaultErrorClassifier classifier = new DefaultErrorClassifier();

    @Test
    void optimisticLockExhaustionIsTransient() {
        assertThat(classifier.classify(new OptimisticLockingFailureException("stale row")))
                .isEqualTo(RetryClassification.TRANSIENT);
    }

    @Test
    void connectionFailuresAreTransient() {
        assertThat(classifier.classify(new ConnectException("refused"))).isEqualTo(RetryClassification.TRANSIENT);
        assertThat(classifier.classify(new TimeoutException("no response"))).isEqualTo(RetryClassification.TRANSIENT);
    }

    @Test
    void explicitTransientMarkerIsHonoured() {
        assertThat(classifier.classify(new TransientMessagingException("try later")))
                .isEqualTo(RetryClassification.TRANSIENT);
    }

    @Test
    void deserializationAndSerializationFailuresAreFatal() {
        assertThat(classifier.classify(new DeserializationException("bad json", new byte[0], false, null)))
                .isEqualTo(RetryClassification.FATAL);
        assertThat(classifier.classify(new SerializationException("cannot serialize")))
                .isEqualTo(RetryClassification.FATAL);
    }

    @Test
    void validationFailureIsFatal() {
        assertThat(classifier.classify(new IllegalArgumentException("negative amount")))
                .isEqualTo(RetryClassification.FATAL);
    }

    @Test
    void explicitFatalMarkerIsHonoured() {
        assertThat(classifier.classify(new FatalMessagingException("never going to work")))
                .isEqualTo(RetryClassification.FATAL);
    }

    @Test
    void anUnrecognisedExceptionDefaultsToFatalRatherThanRetryingForever() {
        assertThat(classifier.classify(new RuntimeException("unexpected bug")))
                .isEqualTo(RetryClassification.FATAL);
    }

    @Test
    void classificationLooksThroughWrapperExceptionsAtTheCause() {
        RuntimeException wrapper = new RuntimeException("listener failed", new ConnectException("refused"));
        assertThat(classifier.classify(wrapper)).isEqualTo(RetryClassification.TRANSIENT);
    }
}
