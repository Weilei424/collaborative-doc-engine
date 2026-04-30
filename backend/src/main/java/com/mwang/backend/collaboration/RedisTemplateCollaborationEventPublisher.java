package com.mwang.backend.collaboration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.CollaborationSessionSnapshot;
import com.mwang.backend.web.model.PresenceEventResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RedisTemplateCollaborationEventPublisher implements RedisCollaborationEventPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String collaborationInstanceId;
    private final Timer publishRedisTimer;
    private final CircuitBreaker circuitBreaker;
    private final Counter circuitOpenCounter;
    private final Counter publishFailuresCounter;

    public RedisTemplateCollaborationEventPublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            String collaborationInstanceId,
            io.micrometer.core.instrument.MeterRegistry meterRegistry,
            CircuitBreaker redisPublishCircuitBreaker,
            Counter redisCircuitOpenCounter,
            Counter redisPublishFailuresCounter) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.collaborationInstanceId = collaborationInstanceId;
        this.publishRedisTimer = Timer.builder("publishRedis").register(meterRegistry);
        this.circuitBreaker = redisPublishCircuitBreaker;
        this.circuitOpenCounter = redisCircuitOpenCounter;
        this.publishFailuresCounter = redisPublishFailuresCounter;
    }

    @Override
    public void publishSessionSnapshot(UUID documentId, CollaborationSessionSnapshot snapshot) {
        publish(new RedisCollaborationEvent(
                collaborationInstanceId,
                RedisCollaborationEventType.SESSION_SNAPSHOT,
                documentId,
                snapshot,
                null,
                null
        ));
    }

    @Override
    public void publishPresenceEvent(PresenceEventResponse event) {
        publish(new RedisCollaborationEvent(
                collaborationInstanceId,
                RedisCollaborationEventType.PRESENCE_UPDATED,
                event.documentId(),
                null,
                event,
                null
        ));
    }

    @Override
    public void publishAccessRevoked(UUID documentId, UUID revokedUserId) {
        publish(new RedisCollaborationEvent(
                collaborationInstanceId,
                RedisCollaborationEventType.ACCESS_REVOKED,
                documentId,
                null,
                null,
                revokedUserId
        ));
    }

    @Override
    public void publishAcceptedOperation(UUID documentId, AcceptedOperationResponse response) {
        String json;
        try {
            json = objectMapper.writeValueAsString(
                    new RedisAcceptedOperationEvent(collaborationInstanceId, response));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize accepted operation event", e);
        }
        String channel = RedisCollaborationChannels.documentOperations(documentId);
        publishRedisTimer.record(() -> circuitBreakingPublish(channel, json));
    }

    private void publish(RedisCollaborationEvent event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize collaboration event", e);
        }
        circuitBreakingPublish(RedisCollaborationChannels.EVENTS, json);
    }

    private void circuitBreakingPublish(String channel, String json) {
        try {
            circuitBreaker.executeRunnable(() -> redisTemplate.convertAndSend(channel, json));
        } catch (CallNotPermittedException e) {
            circuitOpenCounter.increment();
        } catch (Exception ignored) {
            publishFailuresCounter.increment();
            // Redis failure already recorded by circuit breaker; hot path must not fail
        }
    }
}
