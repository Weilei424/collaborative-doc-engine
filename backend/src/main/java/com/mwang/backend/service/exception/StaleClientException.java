package com.mwang.backend.service.exception;

import java.util.UUID;

public class StaleClientException extends RuntimeException {

    private final UUID operationId;
    private final long currentServerVersion;

    public StaleClientException(UUID operationId, long currentServerVersion) {
        super("Client baseVersion is too stale to process; resync required");
        this.operationId = operationId;
        this.currentServerVersion = currentServerVersion;
    }

    public UUID getOperationId() {
        return operationId;
    }

    public long getCurrentServerVersion() {
        return currentServerVersion;
    }
}
