package com.mwang.backend.service.exception;

import java.util.UUID;

public class IdempotentOperationException extends RuntimeException {
    private final UUID operationId;

    public IdempotentOperationException(UUID operationId) {
        super("Operation already accepted: " + operationId);
        this.operationId = operationId;
    }

    public UUID getOperationId() {
        return operationId;
    }
}
