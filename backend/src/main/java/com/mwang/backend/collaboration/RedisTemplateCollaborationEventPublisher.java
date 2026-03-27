package com.mwang.backend.collaboration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.CollaborationSessionSnapshot;
import com.mwang.backend.web.model.PresenceEventResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RedisTemplateCollaborationEventPublisher implements RedisCollaborationEventPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String collaborationInstanceId;

    public RedisTemplateCollaborationEventPublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            String collaborationInstanceId) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.collaborationInstanceId = collaborationInstanceId;
    }

    @Override
    public void publishSessionSnapshot(UUID documentId, CollaborationSessionSnapshot snapshot) {
        publish(new RedisCollaborationEvent(
                collaborationInstanceId,
                RedisCollaborationEventType.SESSION_SNAPSHOT,
                documentId,
                snapshot,
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
                event
        ));
    }

    @Override
    public void publishAcceptedOperation(UUID documentId, AcceptedOperationResponse response) {
        try {
            redisTemplate.convertAndSend(
                    RedisCollaborationChannels.documentOperations(documentId),
                    objectMapper.writeValueAsString(new RedisAcceptedOperationEvent(collaborationInstanceId, response)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to publish accepted operation event", exception);
        }
    }

    private void publish(RedisCollaborationEvent event) {
        try {
            redisTemplate.convertAndSend(RedisCollaborationChannels.EVENTS, objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to publish collaboration event", exception);
        }
    }
}
