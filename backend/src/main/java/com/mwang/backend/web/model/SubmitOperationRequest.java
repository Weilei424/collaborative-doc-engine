package com.mwang.backend.web.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.mwang.backend.domain.DocumentOperationType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SubmitOperationRequest(
        @NotNull UUID operationId,
        @NotNull Long baseVersion,
        @NotNull DocumentOperationType operationType,
        @NotNull JsonNode payload,
        String clientSessionId
) {}
