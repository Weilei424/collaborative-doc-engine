package com.mwang.backend.service.exception;

import java.util.UUID;

public class CollaborationSessionAccessDeniedException extends RuntimeException {
    public CollaborationSessionAccessDeniedException(UUID documentId, UUID sessionId, UUID userId) {
        super("User %s cannot manage session %s for document %s".formatted(userId, sessionId, documentId));
    }
}
