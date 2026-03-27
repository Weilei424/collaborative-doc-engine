package com.mwang.backend.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.service.CollaborationBroadcastService;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class RedisCollaborationEventSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final CollaborationBroadcastService collaborationBroadcastService;
    private final String collaborationInstanceId;

    public RedisCollaborationEventSubscriber(
            ObjectMapper objectMapper,
            CollaborationBroadcastService collaborationBroadcastService,
            String collaborationInstanceId) {
        this.objectMapper = objectMapper;
        this.collaborationBroadcastService = collaborationBroadcastService;
        this.collaborationInstanceId = collaborationInstanceId;
    }

    public void onMessage(RedisCollaborationEvent event) {
        if (event == null || collaborationInstanceId.equals(event.publisherInstanceId())) {
            return;
        }

        if (event.type() == RedisCollaborationEventType.SESSION_SNAPSHOT && event.sessionSnapshot() != null) {
            collaborationBroadcastService.broadcastSessionSnapshot(event.documentId(), event.sessionSnapshot());
            return;
        }

        if (event.type() == RedisCollaborationEventType.PRESENCE_UPDATED && event.presenceEvent() != null) {
            collaborationBroadcastService.broadcastPresenceEvent(event.documentId(), event.presenceEvent());
        }
    }

    public void onMessage(RedisAcceptedOperationEvent event) {
        if (event == null || collaborationInstanceId.equals(event.publisherInstanceId())) {
            return;
        }
        collaborationBroadcastService.broadcastAcceptedOperation(
                event.payload().documentId(), event.payload());
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            if (RedisCollaborationChannels.EVENTS.equals(channel)) {
                onMessage(objectMapper.readValue(body, RedisCollaborationEvent.class));
            } else {
                onMessage(objectMapper.readValue(body, RedisAcceptedOperationEvent.class));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to consume collaboration event", exception);
        }
    }
}
