package com.mwang.backend.config;

import com.mwang.backend.collaboration.RedisCollaborationChannels;
import com.mwang.backend.collaboration.RedisCollaborationEventSubscriber;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.UUID;

@Configuration
public class RedisCollaborationConfig {

    @Bean
    String collaborationInstanceId() {
        return UUID.randomUUID().toString();
    }

    @Bean
    @ConditionalOnProperty(name = "collaboration.redis.listener.enabled", havingValue = "true", matchIfMissing = true)
    RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory redisConnectionFactory,
            RedisCollaborationEventSubscriber redisCollaborationEventSubscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        container.addMessageListener(
                redisCollaborationEventSubscriber,
                new ChannelTopic(RedisCollaborationChannels.EVENTS));
        container.addMessageListener(
                redisCollaborationEventSubscriber,
                new PatternTopic(RedisCollaborationChannels.DOCUMENT_OPERATIONS_PATTERN));
        return container;
    }
}
