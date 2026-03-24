package com.mwang.backend.service;

import com.mwang.backend.web.model.PresenceEventResponse;
import com.mwang.backend.web.model.PresenceUpdateRequest;

import java.util.Map;
import java.util.UUID;

public interface CollaborationPresenceService {
    default PresenceEventResponse publishPresence(UUID documentId, PresenceUpdateRequest request) {
        return publishPresence(documentId, request, null);
    }

    PresenceEventResponse publishPresence(UUID documentId, PresenceUpdateRequest request, Map<String, Object> sessionAttributes);
}