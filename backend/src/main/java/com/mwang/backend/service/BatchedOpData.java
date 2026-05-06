package com.mwang.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.domain.User;

import java.util.UUID;

public record BatchedOpData(
        UUID operationId,
        String clientSessionId,
        long baseVersion,
        DocumentOperationType operationType,
        JsonNode payload,
        User actor,
        long serverVersion
) {}
