package com.mwang.backend.service.exception;

public class InvalidDocumentScopeException extends RuntimeException {
    public InvalidDocumentScopeException(String scope) {
        super("Document scope '%s' is invalid".formatted(scope));
    }
}
