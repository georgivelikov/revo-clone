package com.revolutclone.messaging.spring;

/** The outcome of {@code POST /admin/dlt/{topic}/replay/{offset}}. */
public record ReplayResult(String topic, int partition, long offset, boolean replayed) {
}
