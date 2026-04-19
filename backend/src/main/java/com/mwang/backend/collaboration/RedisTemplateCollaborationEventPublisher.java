package com.mwang.backend.collaboration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.CollaborationSessionSnapshot;
import com.mwang.backend.web.model.PresenceEventResponse;
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

    public RedisTemplateCollaborationEventPublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            String collaborationInstanceId,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.collaborationInstanceId = collaborationInstanceId;
        this.publishRedisTimer = Timer.builder("publishRedis").register(meterRegistry);
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
        publishRedisTimer.record(() -> {
            try {
                redisTemplate.convertAndSend(
                        RedisCollaborationChannels.documentOperations(documentId),
                        objectMapper.writeValueAsString(
                                new RedisAcceptedOperationEvent(collaborationInstanceId, response)));
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new IllegalStateException("Failed to publish accepted operation event", exception);
            }
        });
    }

    private void publish(RedisCollaborationEvent event) {
        try {
            redisTemplate.convertAndSend(RedisCollaborationChannels.EVENTS, objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to publish collaboration event", exception);
        }
    }
}
