package com.mwang.backend.web.model;

import java.util.Map;
import java.util.UUID;

public record PresenceUpdateRequest(UUID sessionId, PresenceType type, Map<String, Object> payload) {
}
