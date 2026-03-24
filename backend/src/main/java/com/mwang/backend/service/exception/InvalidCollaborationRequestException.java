package com.mwang.backend.service.exception;

public class InvalidCollaborationRequestException extends RuntimeException {
    public InvalidCollaborationRequestException(String message) {
        super(message);
    }
}