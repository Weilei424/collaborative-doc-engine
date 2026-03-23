package com.mwang.backend.service.exception;

public class InvalidDocumentRequestException extends RuntimeException {
    public InvalidDocumentRequestException(String message) {
        super(message);
    }
}
