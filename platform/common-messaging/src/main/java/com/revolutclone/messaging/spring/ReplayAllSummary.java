package com.revolutclone.messaging.spring;

/**
 * The outcome of {@code POST /admin/dlt/{topic}/replay-all} - {@code dryRun} echoes back what
 * was requested, so a caller can tell a dry-run summary apart from a real one without having to
 * track the request it made.
 */
public record ReplayAllSummary(String topic, boolean dryRun, int replayedCount) {
}
