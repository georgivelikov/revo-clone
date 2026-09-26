package com.revolutclone.notification.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.revolutclone.notification.fanout.ClientEvent;
import com.revolutclone.notification.fanout.NotificationFanOut;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * plans/phase-03-payments-and-ui.md's test table: <q>valid ticket connects; reused ticket rejected;
 * expired ticket rejected</q> - the phase's own "most fiddly non-obvious piece", which is why it is
 * tested against a real server, a real Redis and a real WebSocket client rather than by calling the
 * interceptor with a mock.
 *
 * <p>Two things make these assertions trustworthy rather than merely green:
 * <ul>
 *   <li><strong>The rejections are distinguished from an unrelated failure by their status code.</strong>
 *       A plain {@code GET} on the handshake URL with a <em>valid</em> ticket answers 400 ("only
 *       upgrade to WebSocket"), because the interceptor let it through and the handshake handler then
 *       refused a non-upgrade request. The same request with a reused or expired ticket answers 401.
 *       Without that contrast a test asserting "the handshake failed" would pass even if the ticket
 *       check had been deleted.</li>
 *   <li><strong>Expiry is tested as expiry.</strong> The ticket's TTL is set to two seconds for this
 *       class and the test waits for Redis itself to drop the key - watched, not redeemed - so the
 *       rejection cannot be the single-use path wearing an expiry costume.</li>
 * </ul>
 *
 * <p>The happy path also asserts what the handshake is <em>for</em>: a frame pushed to that user
 * arrives on their queue, and a frame pushed to another user does not. That is the pair that catches
 * a missing session {@code Principal}, whose symptom is otherwise silent non-delivery - the failure
 * mode the phase's Risks &amp; gotchas warns about for {@code /user/} destinations.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Not a src/test/resources/application.yml: that replaces main's rather than merging with it. See
// docs/spring-boot-4-notes.md.
@TestPropertySource(properties = {
        // No broker in this test: the listeners have nothing to consume and would only spend the
        // test's runtime retrying a connection that will never come up.
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:8180/realms/revolut",
        "WS_ALLOWED_ORIGINS=http://localhost:5173",
        // Short enough for the expiry test to be quick, long enough that the other tests' handshakes
        // are never a race.
        "WS_TICKET_TTL=2s"
})
class WebSocketTicketTest {

    private static final Duration FRAME_TIMEOUT = Duration.ofSeconds(10);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    /**
     * Real Redis, not a fake: single use rests on {@code GETDEL} being atomic on the server, and a
     * stub that returned whatever this test wanted would assert nothing at all.
     */
    @Container
    @ServiceConnection("redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private WebSocketTicketStore tickets;

    @Autowired
    private NotificationFanOut fanOut;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void aValidTicketConnectsAndReceivesOnlyThatUsersEvents() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID strangersPaymentId = UUID.randomUUID();
        String ticket = tickets.issue(userId.toString()).ticket();

        BlockingQueue<String> frames = new ArrayBlockingQueue<>(8);
        StompSession session = connect(ticket, frames);
        assertThat(session.isConnected()).isTrue();

        // Pushed in a loop rather than once, because the simple broker has no queue: a message sent
        // in the window between the socket being connected and the client's SUBSCRIBE frame being
        // processed is dropped, which would make this test flaky in exactly the way that teaches
        // nothing. Both users are pushed to on every attempt, so the isolation assertion below still
        // has something to catch.
        String frame = null;
        for (int attempt = 0; attempt < 20 && frame == null; attempt++) {
            fanOut.push(stranger,
                    ClientEvent.paymentCompleted(strangersPaymentId, 500L, "EUR", Instant.now(), "other"));
            fanOut.push(userId, ClientEvent.paymentCompleted(paymentId, -1000L, "EUR", Instant.now(), "corr-1"));
            frame = frames.poll(500, TimeUnit.MILLISECONDS);
        }

        assertThat(frame)
                .as("a push to this user must arrive on /user/queue/events")
                .isNotNull()
                .contains(paymentId.toString())
                .contains(ClientEvent.PAYMENT_COMPLETED)
                .contains("corr-1")
                .contains("-1000");

        assertThat(frames)
                .as("no frame addressed to another user may reach this session")
                .noneMatch(other -> other.contains(strangersPaymentId.toString()));

        session.disconnect();
    }

    @Test
    void aReusedTicketIsRejected() throws Exception {
        UUID userId = UUID.randomUUID();
        String ticket = tickets.issue(userId.toString()).ticket();

        StompSession session = connect(ticket, new ArrayBlockingQueue<>(1));
        assertThat(session.isConnected()).isTrue();
        session.disconnect();

        assertThat(handshakeStatus(ticket))
                .as("a ticket is single-use: the second handshake must be refused, not upgraded")
                .isEqualTo(401);
        assertThatThrownBy(() -> connect(ticket, new ArrayBlockingQueue<>(1)))
                .as("and no socket may be opened with it")
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void anExpiredTicketIsRejected() throws Exception {
        String ticket = tickets.issue(UUID.randomUUID().toString()).ticket();

        awaitRedisExpiry(ticket);

        assertThat(handshakeStatus(ticket))
                .as("an unused ticket must stop working once its 30-second window (2s here) has passed")
                .isEqualTo(401);
        assertThatThrownBy(() -> connect(ticket, new ArrayBlockingQueue<>(1)))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void aHandshakeWithoutATicketIsRejected() {
        assertThat(handshakeStatus(null))
                .as("the handshake endpoint is permitted anonymously by the filter chain - the ticket "
                        + "is the only thing authenticating it")
                .isEqualTo(401);
        assertThat(handshakeStatus("not-a-ticket-anyone-ever-issued")).isEqualTo(401);
    }

    @Test
    void aValidTicketGetsPastTheInterceptorAndFailsOnlyForNotBeingAnUpgrade() {
        String ticket = tickets.issue(UUID.randomUUID().toString()).ticket();

        // 400, not 401: this is the control that proves the 401s above come from the ticket check
        // rather than from every request to /ws being refused.
        assertThat(handshakeStatus(ticket)).isEqualTo(400);
    }

    @Test
    void theTicketEndpointItselfNeedsAJwt() {
        HttpResponse<Void> response = send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/notifications/ticket"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());

        assertThat(response.statusCode())
                .as("the ticket is what a JWT is exchanged for, so issuing one cannot itself be open")
                .isEqualTo(401);
    }

    /** Opens a real STOMP session over a native WebSocket and subscribes to this user's queue. */
    private StompSession connect(String ticket, BlockingQueue<String> frames) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        // Frames are taken as raw bytes and read as text here. The assertions are about delivery and
        // addressing, not about a client's ability to reconstruct a server-side type - and a
        // StringMessageConverter would actually refuse these frames, since the server sends them as
        // application/json and that converter only accepts text/plain.
        client.setMessageConverter(new SimpleMessageConverter());
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        client.setTaskScheduler(scheduler);

        CompletableFuture<StompSession> session = client.connectAsync(
                "ws://localhost:" + port + "/ws?ticket=" + ticket,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession connected, StompHeaders headers) {
                        connected.subscribe("/user/queue/events", new StompSessionHandlerAdapter() {
                            @Override
                            public Type getPayloadType(StompHeaders frameHeaders) {
                                return byte[].class;
                            }

                            @Override
                            public void handleFrame(StompHeaders frameHeaders, Object payload) {
                                frames.offer(new String((byte[]) payload, StandardCharsets.UTF_8));
                            }
                        });
                    }
                });
        return session.get(FRAME_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    /**
     * The handshake URL fetched as a plain {@code GET}, which reaches the interceptor exactly as a
     * real upgrade would but lets the test read a status code instead of a client-library exception.
     */
    private int handshakeStatus(String ticket) {
        String query = ticket == null ? "" : "?ticket=" + ticket;
        return send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ws" + query))
                .GET()
                .build()).statusCode();
    }

    private static HttpResponse<Void> send(HttpRequest request) {
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    /**
     * Waits for Redis to drop the ticket on its own, watching the key rather than redeeming it - a
     * {@code GETDEL} here would turn this into the single-use test.
     */
    private void awaitRedisExpiry(String ticket) throws InterruptedException {
        String key = WebSocketTicketStore.KEY_PREFIX + ticket;
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (Boolean.TRUE.equals(redis.hasKey(key))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("ticket key never expired in Redis - is the TTL being applied?");
            }
            Thread.sleep(100);
        }
    }
}
