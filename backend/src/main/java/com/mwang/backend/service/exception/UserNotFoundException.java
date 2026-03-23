package com.mwang.backend.service.exception;

import java.util.UUID;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(UUID userId) {
        super("User %s was not found".formatted(userId));
    }
}
