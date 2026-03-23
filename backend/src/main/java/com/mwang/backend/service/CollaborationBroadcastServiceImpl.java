package com.mwang.backend.service;

import com.mwang.backend.web.model.CollaborationSessionSnapshot;
import com.mwang.backend.web.model.PresenceEventResponse;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class CollaborationBroadcastServiceImpl implements CollaborationBroadcastService {

    private final SimpMessagingTemplate messagingTemplate;

    public CollaborationBroadcastServiceImpl(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void broadcastSessionSnapshot(UUID documentId, CollaborationSessionSnapshot snapshot) {
        messagingTemplate.convertAndSend("/topic/documents/" + documentId + "/sessions", snapshot);
    }

    @Override
    public void broadcastPresenceEvent(UUID documentId, PresenceEventResponse event) {
        messagingTemplate.convertAndSend("/topic/documents/" + documentId + "/presence", event);
    }
}
