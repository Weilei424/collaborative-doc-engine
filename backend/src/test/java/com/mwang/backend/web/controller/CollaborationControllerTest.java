package com.mwang.backend.web.controller;

import com.mwang.backend.service.PresenceService;
import com.mwang.backend.web.model.WebSocketMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.UUID;

import static org.mockito.Mockito.*;

class CollaborationControllerTest {

    private CollaborationController collaborationController;
    private PresenceService presenceService;
    private SimpMessagingTemplate messagingTemplate;

    @BeforeEach
    void setUp() {
        presenceService = mock(PresenceService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        collaborationController = new CollaborationController(presenceService, messagingTemplate);
    }

    @Test
    void testHandleJoinMessage() {
        UUID docId = UUID.randomUUID();
        WebSocketMessage message = new WebSocketMessage();
        message.setType(WebSocketMessage.MessageType.JOIN);
        message.setUsername("tester");

        collaborationController.handleMessage(docId, message);

        verify(presenceService).userJoined(docId, "tester");
        verify(presenceService).getActiveUsers(docId);
        String topic = "/topic/document/" + docId + "/presence";
        verify(messagingTemplate).convertAndSend(
                (String) eq(topic),
                (Object) any()
        );
    }

    @Test
    void testHandleLeaveMessage() {
        UUID docId = UUID.randomUUID();
        WebSocketMessage message = new WebSocketMessage();
        message.setType(WebSocketMessage.MessageType.LEAVE);
        message.setUsername("tester");

        collaborationController.handleMessage(docId, message);

        verify(presenceService).userLeft(docId, "tester");
        verify(presenceService).getActiveUsers(docId);
        verify(messagingTemplate).convertAndSend(
                (String) eq("/topic/document/" + docId + "/presence"),
                (Object) any()
        );
    }

    @Test
    void testHandleCursorPositionMessage() {
        UUID docId = UUID.randomUUID();
        WebSocketMessage message = new WebSocketMessage();
        message.setType(WebSocketMessage.MessageType.CURSOR_POSITION);
        message.setUsername("tester");

        collaborationController.handleMessage(docId, message);

        verify(messagingTemplate).convertAndSend(
                "/topic/document/" + docId + "/activity",
                message
        );
    }
}
