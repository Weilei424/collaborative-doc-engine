package com.mwang.backend.service.exception;

import java.util.UUID;

public class DocumentNotFoundException extends RuntimeException {
    public DocumentNotFoundException(UUID documentId) {
        super("Document %s was not found".formatted(documentId));
    }
}
