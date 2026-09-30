package com.revolutclone.notification.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.notification.NotificationServiceApplication;
import com.revolutclone.notification.ws.WebSocketTicketStore;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * plans/phase-07-resilience-and-scale.md's "Done when": "{@code notification-service} delivers
 * correctly with 2+ instances". Two full Spring contexts are booted in this one JVM, sharing one
 * Postgres and one Redis - as close to two real replicas as a single-JVM test gets, and the only
 * way to actually exercise {@link RedisFanOutRelay} rather than assume it: a mocked broker cannot
 * tell you whether one process's publish reaches a session held by a completely different one.
 *
 * <p>Both contexts run with Kafka listeners disabled, exactly like {@code WebSocketTicketTest} -
 * nothing here is about Kafka delivery, only about whether a push made through one instance's
 * {@link NotificationFanOut} reaches a socket that only a different instance holds.
 */
@Testcontainers
class NotificationFanOutMultiInstanceTest {

    private static final Duration FRAME_TIMEOUT = Duration.ofSeconds(10);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    /** Shared by both instances on purpose - the fan-out channel only works because it is the same Redis. */
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private ConfigurableApplicationContext instanceA;
    private ConfigurableApplicationContext instanceB;

    @AfterEach
    void shutdown() {
        if (instanceB != null) {
            instanceB.close();
        }
        if (instanceA != null) {
            instanceA.close();
        }
    }

    @Test
    void aPushMadeThroughOneInstanceIsDeliveredByWhicheverInstanceHoldsTheSocket() throws Exception {
        int portA = freePort();
        instanceA = startInstance(portA);
        instanceB = startInstance(freePort());

        UUID userId = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID strangersPaymentId = UUID.randomUUID();

        // The socket is opened against instance A only - instance B never sees this connection.
        String ticket = instanceA.getBean(WebSocketTicketStore.class).issue(userId.toString()).ticket();
        BlockingQueue<String> frames = new ArrayBlockingQueue<>(8);
        StompSession session = connect(portA, ticket, frames);
        assertThat(session.isConnected()).isTrue();

        // Pushed through instance B's own NotificationFanOut, which holds no socket for this user
        // at all: the frame can only arrive by B publishing to Redis and A's own subscription
        // picking it up. Looped for the same reason WebSocketTicketTest loops its push - the
        // simple broker has no queue, so a frame sent before the client's SUBSCRIBE is processed
        // is simply dropped.
        NotificationFanOut fanOutOnB = instanceB.getBean(NotificationFanOut.class);
        String frame = null;
        for (int attempt = 0; attempt < 20 && frame == null; attempt++) {
            fanOutOnB.push(stranger,
                    ClientEvent.paymentCompleted(strangersPaymentId, 500L, "EUR", Instant.now(), "other"));
            fanOutOnB.push(userId, ClientEvent.paymentCompleted(paymentId, -1000L, "EUR", Instant.now(), "corr-1"));
            frame = frames.poll(500, TimeUnit.MILLISECONDS);
        }

        assertThat(frame)
                .as("a push made through instance B must reach the socket that only instance A holds")
                .isNotNull()
                .contains(paymentId.toString())
                .contains(ClientEvent.PAYMENT_COMPLETED)
                .contains("corr-1")
                .contains("-1000");

        assertThat(frames)
                .as("a push addressed to another user must not leak onto this socket, even across instances")
                .noneMatch(other -> other.contains(strangersPaymentId.toString()));

        session.disconnect();
    }

    private ConfigurableApplicationContext startInstance(int port) {
        Map<String, Object> props = new HashMap<>();
        props.put("SERVER_PORT", String.valueOf(port));
        props.put("NOTIFICATION_DB_URL", POSTGRES.getJdbcUrl());
        props.put("NOTIFICATION_DB_USER", POSTGRES.getUsername());
        props.put("NOTIFICATION_DB_PASSWORD", POSTGRES.getPassword());
        props.put("REDIS_HOST", REDIS.getHost());
        props.put("REDIS_PORT", String.valueOf(REDIS.getFirstMappedPort()));
        // No broker: neither instance has anything to consume, and a real bootstrap-servers value
        // would only spend the test's runtime retrying a connection that never comes up.
        props.put("KAFKA_BOOTSTRAP_SERVERS", "127.0.0.1:9092");
        props.put("spring.kafka.listener.auto-startup", "false");
        props.put("revolut.messaging.outbox.poll-delay-ms", "300000");
        props.put("WS_ALLOWED_ORIGINS", "http://localhost:5173");
        props.put("OIDC_ISSUER_URI", "http://127.0.0.1:8180/realms/revolut");
        return new SpringApplicationBuilder(NotificationServiceApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(props)
                .run();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Opens a real STOMP session over a native WebSocket and subscribes to this user's queue. */
    private StompSession connect(int port, String ticket, BlockingQueue<String> frames) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
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
}
