package com.revolutclone.gateway.idempotency;

import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Keeps a copy of the response body on its way to the client so the gateway can replay it
 * verbatim for a duplicate request. Only ever wrapped around a money-moving POST, so buffering
 * the whole body is bounded by {@code maxBytes} and by the shape of those endpoints - a payment
 * response is a couple of hundred bytes, and this is never in the path of a large or streaming
 * response.
 *
 * <p>The copy is taken by joining the body into one buffer, reading it out, and writing a fresh
 * buffer built from the same bytes. The alternative - peeking at each buffer as it passes - has
 * to reach for {@code DataBuffer.asByteBuffer()}/{@code toByteBuffer()}, both deprecated in
 * Spring Framework 6.0.5 and so on borrowed time in Framework 7. {@code read(byte[])} and
 * {@code bufferFactory().wrap(byte[])} are not.
 */
class CapturingServerHttpResponse extends ServerHttpResponseDecorator {

    private static final byte[] EMPTY = new byte[0];

    private final int maxBytes;

    private byte[] captured = EMPTY;
    private boolean tooLargeToStore;

    CapturingServerHttpResponse(ServerHttpResponse delegate, int maxBytes) {
        super(delegate);
        this.maxBytes = maxBytes;
    }

    @Override
    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
        return DataBufferUtils.join(body)
                .flatMap(joined -> {
                    byte[] bytes = new byte[joined.readableByteCount()];
                    joined.read(bytes);
                    DataBufferUtils.release(joined);
                    if (bytes.length > maxBytes) {
                        tooLargeToStore = true;
                    } else {
                        captured = bytes;
                    }
                    return super.writeWith(Mono.just(bufferFactory().wrap(bytes)));
                })
                // An empty body publisher joins to an empty Mono, and the response still has to
                // be completed - otherwise a 204-shaped downstream response would hang.
                .switchIfEmpty(Mono.defer(() -> super.writeWith(Flux.empty())));
    }

    @Override
    public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
        return writeWith(Flux.from(body).flatMapSequential(publisher -> publisher));
    }

    byte[] capturedBody() {
        return captured;
    }

    boolean isTooLargeToStore() {
        return tooLargeToStore;
    }
}
