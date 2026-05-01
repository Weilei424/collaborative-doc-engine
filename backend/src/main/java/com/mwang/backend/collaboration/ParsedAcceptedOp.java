package com.mwang.backend.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.mwang.backend.domain.DocumentOperationType;

public record ParsedAcceptedOp(DocumentOperationType type, JsonNode payload) {}
