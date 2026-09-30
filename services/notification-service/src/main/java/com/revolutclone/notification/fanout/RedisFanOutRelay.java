package com.revolutclone.notification.fanout;

import com.revolutclone.notification.rates.RateTickBroadcaster;
import com.revolutclone.notification.rates.RateTickFrame;
import com.revolutclone.notification.ws.WebSocketConfig;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * plans/phase-07-resilience-and-scale.md task 9: "a Redis pub/sub channel that every instance
 * subscribes to; the consuming instance publishes to Redis, and whichever instance holds the
 * socket delivers it." Every {@code notification-service} replica both publishes here and
 * listens here - including the instance that published a given message, which turns out to be
 * the point: a Kafka consumer group spreads one user's events across replicas, so the instance
 * that received the event from Kafka is not reliably the one holding that user's socket (and
 * {@code RateTickBroadcaster}'s own rate cache is likewise only as complete as the partitions
 * this instance's Kafka consumer happens to hold). Publishing unconditionally through Redis
 * rather than calling {@code SimpMessagingTemplate} directly means there is exactly one delivery
 * path, and it is the same path whether this process turns out to hold the session or not.
 *
 * <p>This is the fix for the one-replica pin {@code WebSocketConfig} used to document: with it
 * in place, a user's socket can live on any instance and every instance's Kafka consumer or
 * scheduled rate scan can reach it.
 */
@Component
public class RedisFanOutRelay implements MessageListener {

    /** Namespaced away from the ticket store's keyspace, which shares this Redis instance. */
    static final String CHANNEL = "notification:fanout";

    private static final Logger log = LoggerFactory.getLogger(RedisFanOutRelay.class);

    private final StringRedisTemplate redis;
    private final JsonMapper mapper;
    private final SimpMessagingTemplate broker;

    public RedisFanOutRelay(StringRedisTemplate redis, JsonMapper mapper, SimpMessagingTemplate broker) {
        this.redis = redis;
        this.mapper = mapper;
        this.broker = broker;
    }

    /** Publishes a per-user push. Delivered locally only on whichever instance holds that user's socket. */
    public void publishUserEvent(String userId, ClientEvent event) {
        publish(FanOutMessage.userEvent(userId, event));
    }

    /** Publishes a rate-tick broadcast. Every instance delivers it to every socket it holds. */
    public void publishRateTick(RateTickFrame rate) {
        publish(FanOutMessage.rateTick(rate));
    }

    private void publish(FanOutMessage message) {
        try {
            redis.convertAndSend(CHANNEL, mapper.writeValueAsString(message));
        } catch (JacksonException failed) {
            log.warn("failed to publish fan-out message kind={} - this instance's local sockets "
                    + "will miss it unless another replica happens to hold the same session", message.kind(), failed);
        }
    }

    /**
     * Runs on every subscribed instance for every published message, including this instance's
     * own. Delivery to a session this JVM does not hold is a normal no-op, not an error - the
     * simple broker silently drops it, exactly as it always has for a user with no open socket.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        FanOutMessage fanOut;
        try {
            fanOut = mapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8), FanOutMessage.class);
        } catch (JacksonException malformed) {
            log.warn("dropped an unparseable fan-out message on {}", CHANNEL, malformed);
            return;
        }
        switch (fanOut.kind()) {
            case USER_EVENT -> broker.convertAndSendToUser(fanOut.userId(), WebSocketConfig.USER_QUEUE, fanOut.event());
            case RATE_TICK -> broker.convertAndSend(RateTickBroadcaster.DESTINATION, fanOut.rate());
        }
    }
}
