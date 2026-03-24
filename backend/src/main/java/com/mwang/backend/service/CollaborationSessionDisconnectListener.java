package com.mwang.backend.service;

import com.mwang.backend.web.model.CollaborationSessionSnapshot;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;

@Component
public class CollaborationSessionDisconnectListener {

    private final CollaborationSessionService collaborationSessionService;
    private final CollaborationBroadcastService collaborationBroadcastService;

    public CollaborationSessionDisconnectListener(
            CollaborationSessionService collaborationSessionService,
            CollaborationBroadcastService collaborationBroadcastService) {
        this.collaborationSessionService = collaborationSessionService;
        this.collaborationBroadcastService = collaborationBroadcastService;
    }

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return;
        }

        for (CollaborationSessionSnapshot snapshot : collaborationSessionService.cleanupDisconnectedSessions(sessionAttributes)) {
            collaborationBroadcastService.broadcastSessionSnapshot(snapshot.documentId(), snapshot);
        }
    }
}