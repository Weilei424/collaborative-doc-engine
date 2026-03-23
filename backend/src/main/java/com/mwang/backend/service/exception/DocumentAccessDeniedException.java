package com.mwang.backend.service.exception;

import java.util.UUID;

public class DocumentAccessDeniedException extends RuntimeException {
    public DocumentAccessDeniedException(UUID documentId, UUID userId) {
        super("User %s cannot access document %s".formatted(userId, documentId));
    }
}
