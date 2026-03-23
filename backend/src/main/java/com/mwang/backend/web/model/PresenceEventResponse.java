package com.mwang.backend.web.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PresenceEventResponse(
        UUID documentId,
        UUID sessionId,
        UUID userId,
        String username,
        PresenceType type,
        Map<String, Object> payload,
        Instant occurredAt) {
}
