package com.mwang.backend.service;

import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.CollaborationSessionSnapshot;
import com.mwang.backend.web.model.PresenceEventResponse;

import java.util.UUID;

public interface CollaborationBroadcastService {
    void broadcastSessionSnapshot(UUID documentId, CollaborationSessionSnapshot snapshot);

    void broadcastPresenceEvent(UUID documentId, PresenceEventResponse event);

    void broadcastAcceptedOperation(UUID documentId, AcceptedOperationResponse response);

    void broadcastAccessRevoked(UUID documentId, UUID revokedUserId);
}
