package com.mwang.backend.service;

import com.mwang.backend.web.model.CollaborationSessionSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollaborationSessionDisconnectListenerTest {

    @Test
    void disconnectEventCleansTrackedSessionsAndBroadcastsReturnedSnapshots() {
        CollaborationSessionService sessionService = mock(CollaborationSessionService.class);
        CollaborationBroadcastService broadcastService = mock(CollaborationBroadcastService.class);
        CollaborationSessionDisconnectListener listener = new CollaborationSessionDisconnectListener(sessionService, broadcastService);
        HashMap<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionAttributes(sessionAttributes);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        UUID documentId = UUID.randomUUID();
        CollaborationSessionSnapshot snapshot = new CollaborationSessionSnapshot(documentId, List.of());

        when(sessionService.cleanupDisconnectedSessions(sessionAttributes)).thenReturn(List.of(snapshot));

        listener.onSessionDisconnect(new SessionDisconnectEvent(this, message, "stomp-session", CloseStatus.NORMAL));

        verify(sessionService).cleanupDisconnectedSessions(sessionAttributes);
        verify(broadcastService).broadcastSessionSnapshot(documentId, snapshot);
    }
}