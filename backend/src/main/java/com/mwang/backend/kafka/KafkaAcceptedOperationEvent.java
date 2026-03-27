package com.mwang.backend.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.mwang.backend.domain.DocumentOperationType;

import java.time.Instant;
import java.util.UUID;

public record KafkaAcceptedOperationEvent(
        UUID operationId,
        UUID documentId,
        UUID actorUserId,
        String clientSessionId,
        long baseVersion,
        long serverVersion,
        DocumentOperationType operationType,
        JsonNode payload,
        Instant acceptedAt
) {}
