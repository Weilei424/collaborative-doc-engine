package com.mwang.backend.service.exception;

import java.util.UUID;

public class CollaboratorNotFoundException extends RuntimeException {
    public CollaboratorNotFoundException(UUID documentId, UUID userId) {
        super("User %s is not a collaborator on document %s".formatted(userId, documentId));
    }
}
