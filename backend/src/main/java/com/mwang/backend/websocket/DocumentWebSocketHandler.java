package com.mwang.backend.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.web.model.CollaborationMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DocumentWebSocketHandler extends TextWebSocketHandler {
    private static final Map<String, Set<WebSocketSession>> docSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        //todo
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {

        CollaborationMessage msg = objectMapper.readValue(message.getPayload(), CollaborationMessage.class);
        docSessions.computeIfAbsent(msg.getDocumentId(), k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(session);

        for (WebSocketSession s : docSessions.get(msg.getDocumentId())) {
            if (s.isOpen() && s != session) {
                s.sendMessage(message);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        for (Set<WebSocketSession> sessions : docSessions.values()) {
            sessions.remove(session);
        }
    }
}
