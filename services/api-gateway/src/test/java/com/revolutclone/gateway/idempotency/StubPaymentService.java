package com.revolutclone.gateway.idempotency;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for {@code payment-service} behind the gateway. A stub JDK {@link HttpServer} rather
 * than a mocked bean, for the same reason {@code GatewayAuthTest} uses one: the whole point of
 * these tests is the real route/filter/proxy chain, and the assertion that matters most -
 * <em>how many times the downstream service was actually called</em> - is only meaningful if the
 * call is a real one.
 *
 * <p>Every response carries a freshly generated {@code paymentId}, which is what makes a replayed
 * response distinguishable from a second downstream call: identical bodies can only mean the
 * gateway answered from Redis.
 */
final class StubPaymentService implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger postCount = new AtomicInteger();
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();

    /** When non-null, handlers block on it before responding - used to hold a request in flight. */
    private volatile CountDownLatch hold;

    StubPaymentService() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // A pool, not the default single dispatch thread: one held request must not stop the
        // server from serving another.
        this.server.setExecutor(Executors.newCachedThreadPool());
        this.server.createContext("/", exchange -> {
            try (InputStream body = exchange.getRequestBody()) {
                receivedBodies.add(new String(body.readAllBytes(), StandardCharsets.UTF_8));
            }
            postCount.incrementAndGet();

            CountDownLatch gate = hold;
            if (gate != null) {
                try {
                    gate.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            byte[] response = ("{\"paymentId\":\"" + UUID.randomUUID()
                    + "\",\"status\":\"PENDING\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        this.server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    String uri() {
        return "http://127.0.0.1:" + port();
    }

    void holdRequestsUntil(CountDownLatch gate) {
        this.hold = gate;
    }

    /**
     * Waits until at least {@code expected} requests have arrived. Counted rather than latched on
     * purpose: a one-shot "first request arrived" latch is already open once any other test method
     * in the class has run, which would let a test proceed before the request it actually cares
     * about was in flight.
     */
    boolean awaitRequestCount(int expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (postCount.get() < expected && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return postCount.get() >= expected;
    }

    int postCount() {
        return postCount.get();
    }

    List<String> receivedBodies() {
        return List.copyOf(receivedBodies);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
