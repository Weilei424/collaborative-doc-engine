package com.mwang.backend.web.controller;

import com.mwang.backend.service.PresenceService;
import com.mwang.backend.web.model.WebSocketMessage;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Set;
import java.util.UUID;

@RequiredArgsConstructor
@AllArgsConstructor
@Controller
public class CollaborationController {

    private PresenceService presenceService;

    private SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/documents/{documentId}/activity")
    public void handleMessage(@DestinationVariable UUID documentId, @Payload WebSocketMessage message) {
        switch (message.getType()) {
            case JOIN -> {
                presenceService.userJoined(documentId, message.getUsername());
                broadcastPresence(documentId);
            }
            case LEAVE -> {
                presenceService.userLeft(documentId, message.getUsername());
                broadcastPresence(documentId);
            }
            case CURSOR_POSITION, TYPING, IDLE -> {
                messagingTemplate.convertAndSend("/topic/document/" + documentId + "/activity", message);
            }
        }
    }

    private void broadcastPresence(UUID documentId) {
        Set<String> activeUsers = presenceService.getActiveUsers(documentId);
        messagingTemplate.convertAndSend("/topic/document/" + documentId + "/presence", activeUsers);
    }
}
