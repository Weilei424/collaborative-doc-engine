package com.mwang.backend.service.exception;

public class OperationConflictException extends RuntimeException {
    public OperationConflictException(String message) {
        super(message);
    }
}
