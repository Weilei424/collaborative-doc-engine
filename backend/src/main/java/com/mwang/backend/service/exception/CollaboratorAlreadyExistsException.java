package com.mwang.backend.service.exception;

import java.util.UUID;

public class CollaboratorAlreadyExistsException extends RuntimeException {
    public CollaboratorAlreadyExistsException(UUID documentId, UUID userId) {
        super("User %s is already a collaborator on document %s".formatted(userId, documentId));
    }
}
