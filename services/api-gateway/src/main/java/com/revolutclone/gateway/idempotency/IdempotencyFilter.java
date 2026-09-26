package com.revolutclone.gateway.idempotency;

import com.revolutclone.gateway.correlation.CorrelationWebFilter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Layer 1 of plans/phase-03-payments-and-ui.md's three-layer idempotency: the edge filter on
 * money-moving POSTs, keyed on {@code (jwt.sub, Idempotency-Key)} and backed by Redis.
 *
 * <ul>
 *   <li>First request for a key: {@code SET NX} an {@code IN_PROGRESS} marker carrying the
 *       request-body hash, 24 h TTL, then let the request through and store the response against
 *       the marker once it comes back.</li>
 *   <li>Concurrent duplicate: <strong>409</strong>, {@code IN_PROGRESS}. The first request has not
 *       been answered yet, so there is no response to replay and inventing one would be a lie.</li>
 *   <li>Completed duplicate: the stored status and body, replayed <strong>verbatim</strong>,
 *       without the downstream service being touched at all.</li>
 *   <li>Same key, different body: <strong>422</strong>. That is a client bug - a key is a promise
 *       that this is the same request - and silently replaying the old response hides it.</li>
 * </ul>
 *
 * <p><strong>This layer is an optimisation, not the guarantee.</strong> Redis is not durable
 * enough for money and may be flushed at any moment; every path below therefore degrades towards
 * "let the request through" rather than towards rejecting it, and correctness comes from layer 2,
 * payment-service's {@code unique (initiator_user_id, idempotency_key)}. A Redis outage, an
 * unreadable marker, a marker whose TTL expired between the {@code SET NX} and the read: all
 * proceed, and the database still refuses to create a second payment. The one thing this filter
 * must never do is turn a Redis problem into a 5xx on a transfer.
 *
 * <p>The marker is <em>deleted</em> rather than completed when the request fails in a way that
 * says nothing about whether the instruction was accepted - a 5xx, a 429 from the rate limiter,
 * a 408, or an error signal in the filter chain. Storing a transient failure as the replayable
 * answer for 24 h would mean a client that retried correctly kept being handed back the outage.
 * Dropping the marker sends the retry to layer 2, which returns the real payment if the first
 * attempt did in fact get through.
 *
 * <h2>Order</h2>
 * {@link #ORDER} sits below {@code NettyWriteResponseFilter}'s {@code -1} on purpose. That filter
 * writes the downstream response into {@code exchange.getResponse()} captured from the exchange
 * <em>it</em> was handed, so a response decorator installed by a later (higher-order) filter is
 * simply not the object it writes to and captures nothing. Being early also means this filter
 * runs before the per-route {@code RequestRateLimiter} (route filters order from 1 upwards),
 * which is exactly why a 429 has to be handled as a marker-deleting outcome above.
 */
@Component
public class IdempotencyFilter implements GlobalFilter, Ordered {

    /** See the class-level note on ordering - must be below {@code NettyWriteResponseFilter}'s -1. */
    public static final int ORDER = -100;

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /**
     * Set on a replayed response. Not part of the replayed payload - status, body and
     * {@code Content-Type} are byte-for-byte what the first request got - but a client, and the
     * dev drawer in task 03.11, benefits from being able to tell a replay from a fresh answer.
     */
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private static final String KEY_PREFIX = "idem:";
    private static final String MDC_CORRELATION_KEY = "correlationId";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private final ReactiveStringRedisTemplate redis;
    private final IdempotencyProperties properties;
    private final List<PathPattern> guardedPaths;

    /**
     * {@code ReactiveStringRedisTemplate} is injected rather than declared here: Spring Cloud
     * Gateway's own {@code RedisRateLimiter} - already in use on every route in this service -
     * requires one too, so the bean provably exists in this context. Declaring a second one would
     * make that injection ambiguous and break the rate limiter.
     */
    public IdempotencyFilter(ReactiveStringRedisTemplate redis, IdempotencyProperties properties) {
        this.redis = redis;
        this.properties = properties;
        PathPatternParser parser = PathPatternParser.defaultInstance;
        this.guardedPaths = properties.paths().stream().map(parser::parse).toList();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!isMoneyMovingPost(exchange.getRequest())) {
            return chain.filter(exchange);
        }

        String rawKey = exchange.getRequest().getHeaders().getFirst(IDEMPOTENCY_KEY_HEADER);
        if (rawKey == null || rawKey.isBlank()) {
            // CLAUDE.md hard rule 3. Rejected at the edge so the downstream service never sees a
            // money-moving POST it cannot deduplicate; payment-service refuses it too (its
            // MissingIdempotencyKeyException), because the gateway is not the trust boundary.
            return ProblemResponse.write(exchange, HttpStatus.BAD_REQUEST, "missing-idempotency-key",
                    "Missing Idempotency-Key",
                    "This endpoint requires a non-blank Idempotency-Key header. Generate one "
                            + "client-side and reuse it verbatim on every retry.");
        }
        String key = rawKey.strip();
        if (key.length() > properties.maxKeyLength()) {
            return ProblemResponse.write(exchange, HttpStatus.BAD_REQUEST, "invalid-idempotency-key",
                    "Invalid Idempotency-Key", "The Idempotency-Key header is too long.");
        }

        return currentSubject(exchange)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(subject -> subject
                        .map(sub -> guard(exchange, chain, sub, key))
                        // Unreachable while the security config authenticates every route but
                        // /api/identity/register: a money-moving POST cannot arrive unauthenticated.
                        // If a later phase adds one, a missing subject must not silently become a
                        // shared idempotency namespace across users - so the request passes through
                        // to the service's own layer-2 check instead.
                        .orElseGet(() -> {
                            logWithCorrelation(exchange, () -> log.warn(
                                    "Money-moving POST {} has no authenticated subject; "
                                            + "edge idempotency skipped, service layer still applies",
                                    exchange.getRequest().getPath().value()));
                            return chain.filter(exchange);
                        }));
    }

    private boolean isMoneyMovingPost(ServerHttpRequest request) {
        if (!HttpMethod.POST.equals(request.getMethod())) {
            return false;
        }
        return guardedPaths.stream().anyMatch(pattern -> pattern.matches(request.getPath().pathWithinApplication()));
    }

    private Mono<String> currentSubject(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(token -> token.getToken().getSubject());
    }

    private Mono<Void> guard(ServerWebExchange exchange, GatewayFilterChain chain, String subject, String key) {
        // The subject is a JWT `sub` (a Keycloak user id, no colons), so prefix + sub + ':' + key
        // cannot collide across users whatever the client puts in the header. Note this key is
        // scoped per user, which is exactly why it must never be forwarded to the ledger, whose
        // journal_entry.idempotency_key is globally unique - payment-service derives that one as
        // `payment:<paymentId>` instead.
        String redisKey = KEY_PREFIX + subject + ':' + key;

        return requestBody(exchange).flatMap(body -> {
            String bodyHash = sha256Hex(body);
            ServerWebExchange withReplayableBody = exchange.mutate()
                    .request(cachingRequest(exchange, body))
                    .build();

            return decide(redisKey, bodyHash)
                    .onErrorResume(redisUnavailable -> {
                        logWithCorrelation(exchange, () -> log.warn(
                                "Redis unavailable for edge idempotency on {}; proceeding - the "
                                        + "service's unique constraint is the real guarantee: {}",
                                exchange.getRequest().getPath().value(), redisUnavailable.toString()));
                        return Mono.just(Decision.proceedUntracked());
                    })
                    .flatMap(decision -> switch (decision.kind()) {
                        case PROCEED -> proceed(withReplayableBody, chain, redisKey, bodyHash, decision.tracked());
                        case IN_PROGRESS -> inProgress(exchange);
                        case BODY_MISMATCH -> bodyMismatch(exchange);
                        case REPLAY -> replay(exchange, decision.stored());
                    });
        }).onErrorResume(DataBufferLimitException.class, tooBig -> ProblemResponse.write(
                exchange, HttpStatus.PAYLOAD_TOO_LARGE, "request-too-large",
                "Request too large", "The request body is too large for this endpoint."));
    }

    /** Reads the body into memory so it can be hashed, then replayed to the downstream service. */
    private Mono<byte[]> requestBody(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody(), properties.maxBufferedBytes())
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return bytes;
                })
                .defaultIfEmpty(new byte[0]);
    }

    /**
     * The request the downstream service actually receives: identical headers, with a body that can
     * be subscribed to again now that the original stream has been consumed for hashing.
     */
    private ServerHttpRequest cachingRequest(ServerWebExchange exchange, byte[] body) {
        return new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                if (body.length == 0) {
                    return Flux.empty();
                }
                // Deferred so a retried or re-subscribed route gets a fresh buffer over the same
                // bytes rather than an already-drained one.
                return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(body)));
            }
        };
    }

    /**
     * Claims the key, or works out what the existing marker means. {@code SET NX} is what makes
     * "first request wins" atomic across gateway instances - two concurrent duplicates cannot both
     * see an empty key.
     */
    private Mono<Decision> decide(String redisKey, String bodyHash) {
        return redis.opsForValue()
                .setIfAbsent(redisKey, IdempotencyRecord.inProgress(bodyHash).encode(), properties.ttl())
                .flatMap(claimed -> Boolean.TRUE.equals(claimed)
                        ? Mono.just(Decision.proceedTracked())
                        : classifyExisting(redisKey, bodyHash));
    }

    private Mono<Decision> classifyExisting(String redisKey, String bodyHash) {
        return redis.opsForValue().get(redisKey)
                .map(raw -> IdempotencyRecord.decode(raw)
                        .map(stored -> classify(stored, bodyHash))
                        // Unreadable marker: treat the request as new. Degrading towards letting a
                        // transfer through is the right direction; layer 2 still deduplicates.
                        .orElseGet(Decision::proceedUntracked))
                // The marker expired between the SET NX and this read. Same reasoning.
                .defaultIfEmpty(Decision.proceedUntracked());
    }

    private Decision classify(IdempotencyRecord stored, String bodyHash) {
        if (!stored.bodyHash().equals(bodyHash)) {
            // Checked before the in-progress/completed split: a different body under the same key
            // is a client bug whichever state the first request is in.
            return Decision.bodyMismatch();
        }
        return stored.state() == IdempotencyRecord.State.COMPLETED
                ? Decision.replay(stored)
                : Decision.inProgress();
    }

    private Mono<Void> proceed(ServerWebExchange exchange, GatewayFilterChain chain,
                               String redisKey, String bodyHash, boolean tracked) {
        if (!tracked) {
            return chain.filter(exchange);
        }
        CapturingServerHttpResponse capturing =
                new CapturingServerHttpResponse(exchange.getResponse(), properties.maxBufferedBytes());
        ServerWebExchange captured = exchange.mutate().response(capturing).build();

        return chain.filter(captured)
                .then(Mono.defer(() -> storeOutcome(exchange, redisKey, bodyHash, capturing)))
                .onErrorResume(failed -> release(redisKey).then(Mono.error(failed)));
    }

    private Mono<Void> storeOutcome(ServerWebExchange exchange, String redisKey, String bodyHash,
                                    CapturingServerHttpResponse capturing) {
        HttpStatusCode statusCode = capturing.getStatusCode();
        int status = statusCode == null ? HttpStatus.OK.value() : statusCode.value();

        if (!isReplayable(status) || capturing.isTooLargeToStore()) {
            return release(redisKey);
        }
        String contentType = capturing.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        String marker = IdempotencyRecord
                .completed(bodyHash, status, contentType, capturing.capturedBody())
                .encode();
        return redis.opsForValue().set(redisKey, marker, properties.ttl())
                .onErrorResume(redisUnavailable -> {
                    // The client already has its response; the only loss is the replay.
                    logWithCorrelation(exchange, () -> log.warn(
                            "Could not store the idempotency marker for a completed request: {}",
                            redisUnavailable.toString()));
                    return Mono.just(Boolean.FALSE);
                })
                .then();
    }

    /**
     * Whether a status is a real answer from the system rather than a transient refusal. 5xx, 429
     * and 408 say nothing about whether the instruction was accepted, so they are never replayed.
     */
    private boolean isReplayable(int status) {
        return status < 500
                && status != HttpStatus.TOO_MANY_REQUESTS.value()
                && status != HttpStatus.REQUEST_TIMEOUT.value();
    }

    private Mono<Void> release(String redisKey) {
        return redis.delete(redisKey).onErrorReturn(0L).then();
    }

    private Mono<Void> inProgress(ServerWebExchange exchange) {
        return ProblemResponse.write(exchange, HttpStatus.CONFLICT, "idempotency-in-progress",
                "IN_PROGRESS",
                "A request with this Idempotency-Key is still being processed. Retry shortly, or "
                        + "poll the payment; do not generate a new key.");
    }

    private Mono<Void> bodyMismatch(ServerWebExchange exchange) {
        logWithCorrelation(exchange, () -> log.warn(
                "Idempotency-Key reused with a different request body on {} - rejected with 422",
                exchange.getRequest().getPath().value()));
        return ProblemResponse.write(exchange, HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-key-reused",
                "Idempotency-Key reused",
                "This Idempotency-Key was already used for a request with a different body. A key "
                        + "identifies one specific request; generate a new key for a new request.");
    }

    private Mono<Void> replay(ServerWebExchange exchange, IdempotencyRecord stored) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatusCode.valueOf(stored.status()));
        if (!stored.contentType().isBlank()) {
            response.getHeaders().set(HttpHeaders.CONTENT_TYPE, stored.contentType());
        }
        response.getHeaders().set(REPLAYED_HEADER, "true");
        if (stored.body().length == 0) {
            return response.setComplete();
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(stored.body())));
    }

    private static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is mandatory in every JRE.
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /**
     * CLAUDE.md rule 10 wants {@code correlationId} as a real field on every log line. MDC is
     * thread-local and Reactor hops threads, so it is set around the individual log call the same
     * way {@link CorrelationWebFilter} does it.
     */
    private void logWithCorrelation(ServerWebExchange exchange, Runnable logging) {
        String correlationId = exchange.getRequest().getHeaders().getFirst(CorrelationWebFilter.HEADER);
        if (correlationId == null) {
            logging.run();
            return;
        }
        MDC.put(MDC_CORRELATION_KEY, correlationId);
        try {
            logging.run();
        } finally {
            MDC.remove(MDC_CORRELATION_KEY);
        }
    }

    /**
     * What to do with this request. {@code tracked} distinguishes "we own the marker and must
     * finish it" from "proceed without a marker", the degraded path taken whenever Redis could not
     * give a trustworthy answer.
     */
    private record Decision(Kind kind, boolean tracked, IdempotencyRecord stored) {

        enum Kind {
            PROCEED,
            IN_PROGRESS,
            BODY_MISMATCH,
            REPLAY
        }

        static Decision proceedTracked() {
            return new Decision(Kind.PROCEED, true, null);
        }

        static Decision proceedUntracked() {
            return new Decision(Kind.PROCEED, false, null);
        }

        static Decision inProgress() {
            return new Decision(Kind.IN_PROGRESS, false, null);
        }

        static Decision bodyMismatch() {
            return new Decision(Kind.BODY_MISMATCH, false, null);
        }

        static Decision replay(IdempotencyRecord stored) {
            return new Decision(Kind.REPLAY, false, stored);
        }
    }
}
