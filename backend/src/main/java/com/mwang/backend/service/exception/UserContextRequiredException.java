package com.mwang.backend.service.exception;

public class UserContextRequiredException extends RuntimeException {
    public UserContextRequiredException() {
        this("X-User-Id header is required");
    }

    public UserContextRequiredException(String message) {
        super(message);
    }
}
