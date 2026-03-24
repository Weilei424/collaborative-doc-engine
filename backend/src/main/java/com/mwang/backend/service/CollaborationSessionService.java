package com.mwang.backend.service;

import com.mwang.backend.web.model.CollaborationSessionSnapshot;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface CollaborationSessionService {
    CollaborationSessionSnapshot join(UUID documentId, UUID clientSessionHint);

    CollaborationSessionSnapshot join(UUID documentId, UUID clientSessionHint, Map<String, Object> sessionAttributes);

    CollaborationSessionSnapshot leave(UUID documentId, UUID sessionId);

    CollaborationSessionSnapshot leave(UUID documentId, UUID sessionId, Map<String, Object> sessionAttributes);

    List<CollaborationSessionSnapshot> cleanupDisconnectedSessions(Map<String, Object> sessionAttributes);
}