package com.mwang.backend.web.model;

import java.time.Instant;
import java.util.UUID;

public record CollaborationSessionResponse(
        UUID sessionId,
        UUID documentId,
        UUID userId,
        String username,
        Instant joinedAt,
        Instant lastSeenAt) {
}
