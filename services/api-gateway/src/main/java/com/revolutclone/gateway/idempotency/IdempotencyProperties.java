package com.revolutclone.gateway.idempotency;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for layer 1 of plans/phase-03-payments-and-ui.md's three-layer idempotency - the
 * edge filter. Layers 2 and 3 (the {@code unique (initiator_user_id, idempotency_key)} constraint
 * in payment-service and {@code processed_event} in every consumer) are elsewhere and are the
 * layers that actually guarantee correctness; everything configured here is an optimisation that
 * may be flushed at any time.
 *
 * @param paths     path patterns the filter applies to. Deliberately a list rather than a
 *                  hard-coded {@code /api/payments/**}: every later phase adds another
 *                  money-moving POST (FX execute in 04, vault transfers in 06), and the phase
 *                  spec's rule is "a filter on money-moving POSTs", not "a filter on payments".
 *                  Non-POST requests are never touched whatever this contains - a GET is
 *                  naturally idempotent and paying the Redis round trip on the read path would
 *                  be pure cost.
 * @param ttl       how long a marker lives. The phase spec fixes this at 24 h: long enough that
 *                  any realistic client retry - including a user reopening the app the next
 *                  morning on the same pending transfer - still hits the replay, short enough
 *                  that Redis does not accumulate markers forever.
 * @param maxKeyLength a cap on the client-supplied header, so a hostile or buggy client cannot
 *                  turn one POST into a multi-megabyte Redis key. 200 is far above any UUID.
 * @param maxBufferedBytes the ceiling on what this filter will hold in memory for one request, in
 *                  both directions. A request body larger than this is refused with 413 rather
 *                  than buffered to hash it; a response larger than this is passed through to the
 *                  client but not stored for replay - the marker is dropped instead, so a
 *                  duplicate falls through to layer 2, which returns the same payment from
 *                  Postgres anyway. Payment requests and responses are a few hundred bytes.
 */
@ConfigurationProperties(prefix = "revolut.gateway.idempotency")
public record IdempotencyProperties(
        @DefaultValue("/api/payments") List<String> paths,
        @DefaultValue("24h") Duration ttl,
        @DefaultValue("200") int maxKeyLength,
        @DefaultValue("65536") int maxBufferedBytes) {
}
