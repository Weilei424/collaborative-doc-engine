package com.mwang.backend.service;

import com.mwang.backend.web.model.PresenceEventResponse;
import com.mwang.backend.web.model.PresenceUpdateRequest;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.util.UUID;

public interface CollaborationPresenceService {
    PresenceEventResponse publishPresence(UUID documentId, PresenceUpdateRequest request, SimpMessageHeaderAccessor headerAccessor);
}
