package com.mwang.backend.collaboration;

import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.CollaborationSessionSnapshot;
import com.mwang.backend.web.model.PresenceEventResponse;

import java.util.UUID;

public interface RedisCollaborationEventPublisher {
    void publishSessionSnapshot(UUID documentId, CollaborationSessionSnapshot snapshot);

    void publishPresenceEvent(PresenceEventResponse event);

    void publishAcceptedOperation(UUID documentId, AcceptedOperationResponse response);

    void publishAccessRevoked(UUID documentId, UUID revokedUserId);
}
