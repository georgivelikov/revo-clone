package com.revolutclone.notification.fanout;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Subscribes {@link RedisFanOutRelay} to {@link RedisFanOutRelay#CHANNEL} on startup.
 * {@code RedisMessageListenerContainer} is not autoconfigured by Boot the way
 * {@code StringRedisTemplate} is - a service that only ever published would never need one - so
 * this is the one bean {@code common-messaging}-style autoconfiguration does not already supply.
 */
@Configuration
public class FanOutRedisConfig {

    @Bean
    public RedisMessageListenerContainer fanOutListenerContainer(RedisConnectionFactory connectionFactory,
            RedisFanOutRelay relay) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(relay, new ChannelTopic(RedisFanOutRelay.CHANNEL));
        return container;
    }
}
