package com.mwang.backend.collaboration;

import com.mwang.backend.web.model.CollaborationSessionResponse;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CollaborationSessionStore {
    void save(CollaborationSessionResponse session);

    void remove(UUID documentId, UUID sessionId);

    Optional<CollaborationSessionResponse> findBySessionId(UUID documentId, UUID sessionId);

    List<CollaborationSessionResponse> findByDocumentId(UUID documentId);
}
