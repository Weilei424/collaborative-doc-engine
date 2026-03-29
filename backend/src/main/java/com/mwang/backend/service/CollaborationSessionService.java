package com.mwang.backend.service;

import com.mwang.backend.web.model.CollaborationSessionSnapshot;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface CollaborationSessionService {
    CollaborationSessionSnapshot join(UUID documentId);

    CollaborationSessionSnapshot join(UUID documentId, SimpMessageHeaderAccessor headerAccessor);

    CollaborationSessionSnapshot leave(UUID documentId, UUID sessionId);

    CollaborationSessionSnapshot leave(UUID documentId, UUID sessionId, SimpMessageHeaderAccessor headerAccessor);

    List<CollaborationSessionSnapshot> cleanupDisconnectedSessions(Map<String, Object> sessionAttributes);
}
