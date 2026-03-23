package com.mwang.backend.web.controller;

public enum DocumentListScope {
    OWNED,
    SHARED,
    ACCESSIBLE,
    PUBLIC;

    public static DocumentListScope from(String rawScope) {
        if (rawScope == null || rawScope.isBlank()) {
            return ACCESSIBLE;
        }

        try {
            return DocumentListScope.valueOf(rawScope.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new com.mwang.backend.service.exception.InvalidDocumentScopeException(rawScope);
        }
    }
}
