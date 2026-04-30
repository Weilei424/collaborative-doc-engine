package com.mwang.backend.config;

import com.mwang.backend.collaboration.RedisCollaborationChannels;
import com.mwang.backend.collaboration.RedisCollaborationEventSubscriber;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;
import java.util.UUID;

@Configuration
public class RedisCollaborationConfig {

    @Bean
    String collaborationInstanceId() {
        return UUID.randomUUID().toString();
    }

    @Bean
    LettuceClientConfigurationBuilderCustomizer lettuceDisconnectedBehavior(
            @Value("${collaboration.redis.command-timeout-ms:2000}") long commandTimeoutMs) {
        return builder -> builder.clientOptions(
                ClientOptions.builder()
                        .autoReconnect(true)
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(commandTimeoutMs)))
                        .build());
    }

    @Bean
    @ConditionalOnProperty(name = "collaboration.redis.listener.enabled", havingValue = "true", matchIfMissing = true)
    RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory redisConnectionFactory,
            RedisCollaborationEventSubscriber redisCollaborationEventSubscriber,
            @Value("${collaboration.redis.listener.recovery-interval-ms:5000}") long recoveryIntervalMs) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        container.setRecoveryInterval(recoveryIntervalMs);
        container.addMessageListener(
                redisCollaborationEventSubscriber,
                new ChannelTopic(RedisCollaborationChannels.EVENTS));
        container.addMessageListener(
                redisCollaborationEventSubscriber,
                new PatternTopic(RedisCollaborationChannels.DOCUMENT_OPERATIONS_PATTERN));
        return container;
    }
}
