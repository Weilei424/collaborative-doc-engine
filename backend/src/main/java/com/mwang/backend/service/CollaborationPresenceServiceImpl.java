package com.mwang.backend.service;

import com.mwang.backend.collaboration.CollaborationSessionStore;
import com.mwang.backend.collaboration.RedisCollaborationEventPublisher;
import com.mwang.backend.service.exception.CollaborationSessionNotFoundException;
import com.mwang.backend.service.exception.InvalidPresenceUpdateException;
import com.mwang.backend.web.model.CollaborationSessionResponse;
import com.mwang.backend.web.model.PresenceEventResponse;
import com.mwang.backend.web.model.PresenceUpdateRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class CollaborationPresenceServiceImpl implements CollaborationPresenceService {

    private final CollaborationSessionStore collaborationSessionStore;
    private final RedisCollaborationEventPublisher eventPublisher;

    public CollaborationPresenceServiceImpl(
            CollaborationSessionStore collaborationSessionStore,
            RedisCollaborationEventPublisher eventPublisher) {
        this.collaborationSessionStore = collaborationSessionStore;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public PresenceEventResponse publishPresence(UUID documentId, PresenceUpdateRequest request) {
        if (request == null || request.sessionId() == null || request.type() == null) {
            throw new InvalidPresenceUpdateException("Presence update requires sessionId and type");
        }

        CollaborationSessionResponse session = collaborationSessionStore.findBySessionId(documentId, request.sessionId())
                .orElseThrow(() -> new CollaborationSessionNotFoundException(documentId, request.sessionId()));

        PresenceEventResponse event = new PresenceEventResponse(
                documentId,
                session.sessionId(),
                session.userId(),
                session.username(),
                request.type(),
                request.payload() == null ? Map.of() : request.payload(),
                Instant.now()
        );
        eventPublisher.publishPresenceEvent(event);
        return event;
    }
}
