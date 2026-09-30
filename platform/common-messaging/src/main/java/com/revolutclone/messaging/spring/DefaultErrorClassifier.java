package com.revolutclone.messaging.spring;

import com.revolutclone.messaging.api.ErrorClassifier;
import com.revolutclone.messaging.api.FatalMessagingException;
import com.revolutclone.messaging.api.RetryClassification;
import com.revolutclone.messaging.api.TransientMessagingException;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.support.serializer.DeserializationException;
import tools.jackson.core.JacksonException;

/**
 * The concrete rules behind plans/phase-07-resilience-and-scale.md's classification table.
 * Walks the cause chain rather than only the top-level exception, since a listener's own
 * exception is frequently a wrapper (e.g. Spring Kafka's {@code ListenerExecutionFailedException})
 * around the one that actually carries the signal.
 *
 * <p><strong>Deserialization failures never reach this class in practice</strong> - with
 * {@code ErrorHandlingDeserializer} configured (phase 07 task 2), a poison message is already
 * turned into a {@link DeserializationException} and recovered before a listener method is even
 * invoked. The check here is defence in depth for a service that has not wired that up yet, not
 * the primary mechanism.
 *
 * <p>Only exception types already on this module's classpath are checked here (Spring's data
 * access hierarchy, the JDK, Kafka's own client library, Jackson 3). A service with a
 * framework-specific transient signal this class cannot see - an HTTP 503 from a synchronous
 * downstream call, say - should catch it at the call site and rethrow
 * {@link TransientMessagingException} rather than have this module depend on every HTTP client
 * library to recognise it centrally.
 *
 * <p>Anything unrecognised is classified {@link RetryClassification#FATAL}. An unknown exception
 * is more likely a bug that will fail identically every time than a transient condition worth
 * three topic hops to discover - the safer default is straight to the DLT where a human looks at
 * it, not an infinite retry loop.
 */
public class DefaultErrorClassifier implements ErrorClassifier {

    @Override
    public RetryClassification classify(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof FatalMessagingException) {
                return RetryClassification.FATAL;
            }
            if (cause instanceof TransientMessagingException) {
                return RetryClassification.TRANSIENT;
            }
            if (cause instanceof DeserializationException || cause instanceof SerializationException) {
                return RetryClassification.FATAL;
            }
            if (cause instanceof JacksonException) {
                return RetryClassification.FATAL;
            }
            if (cause instanceof IllegalArgumentException) {
                return RetryClassification.FATAL;
            }
            if (cause instanceof TransientDataAccessException) {
                return RetryClassification.TRANSIENT;
            }
            if (cause instanceof RetriableException) {
                return RetryClassification.TRANSIENT;
            }
            if (cause instanceof ConnectException || cause instanceof TimeoutException) {
                return RetryClassification.TRANSIENT;
            }
        }
        return RetryClassification.FATAL;
    }
}
