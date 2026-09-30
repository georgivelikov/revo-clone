package com.revolutclone.messaging.api;

/**
 * Decides whether a consumer's thrown exception is worth retrying or should go straight to the
 * dead-letter topic. No Kafka type appears here so the classification rules stay unit-testable
 * with no broker, same as everything else under {@code api/}.
 */
@FunctionalInterface
public interface ErrorClassifier {

    RetryClassification classify(Throwable exception);
}
