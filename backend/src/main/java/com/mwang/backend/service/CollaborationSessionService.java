package com.mwang.backend.service;

import com.mwang.backend.web.model.CollaborationSessionSnapshot;

import java.util.UUID;

public interface CollaborationSessionService {
    CollaborationSessionSnapshot join(UUID documentId, UUID clientSessionHint);

    CollaborationSessionSnapshot leave(UUID documentId, UUID sessionId);
}
