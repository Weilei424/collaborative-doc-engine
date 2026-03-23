package com.mwang.backend.collaboration;

import com.mwang.backend.web.model.CollaborationSessionSnapshot;
import com.mwang.backend.web.model.PresenceEventResponse;

import java.util.UUID;

public record RedisCollaborationEvent(
        String publisherInstanceId,
        RedisCollaborationEventType type,
        UUID documentId,
        CollaborationSessionSnapshot sessionSnapshot,
        PresenceEventResponse presenceEvent) {
}
