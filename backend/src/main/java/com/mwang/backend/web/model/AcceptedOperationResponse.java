package com.mwang.backend.web.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.mwang.backend.domain.DocumentOperationType;

import java.time.Instant;
import java.util.UUID;

public record AcceptedOperationResponse(
        UUID operationId,
        UUID documentId,
        long serverVersion,
        DocumentOperationType operationType,
        JsonNode transformedPayload,
        UUID actorUserId,
        String clientSessionId,
        Instant acceptedAt
) {}
