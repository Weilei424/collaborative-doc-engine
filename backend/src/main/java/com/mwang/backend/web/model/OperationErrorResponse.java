package com.mwang.backend.web.model;

import java.util.UUID;

public record OperationErrorResponse(String error, UUID operationId, long currentServerVersion) {}
