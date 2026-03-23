package com.mwang.backend.service.exception;

import java.util.UUID;

public class CollaborationSessionNotFoundException extends RuntimeException {
    public CollaborationSessionNotFoundException(UUID documentId, UUID sessionId) {
        super("Session %s was not found for document %s".formatted(sessionId, documentId));
    }
}
