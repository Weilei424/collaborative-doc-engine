package com.mwang.backend.service;

import com.mwang.backend.collaboration.CollaborationSessionStore;
import com.mwang.backend.collaboration.RedisCollaborationEventPublisher;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.service.exception.CollaborationSessionAccessDeniedException;
import com.mwang.backend.service.exception.CollaborationSessionNotFoundException;
import com.mwang.backend.service.exception.DocumentNotFoundException;
import com.mwang.backend.service.exception.InvalidPresenceUpdateException;
import com.mwang.backend.web.model.CollaborationSessionResponse;
import com.mwang.backend.web.model.PresenceEventResponse;
import com.mwang.backend.web.model.PresenceUpdateRequest;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class CollaborationPresenceServiceImpl implements CollaborationPresenceService {

    private final DocumentRepository documentRepository;
    private final CurrentUserProvider currentUserProvider;
    private final DocumentAuthorizationService documentAuthorizationService;
    private final CollaborationSessionStore collaborationSessionStore;
    private final RedisCollaborationEventPublisher eventPublisher;

    public CollaborationPresenceServiceImpl(
            DocumentRepository documentRepository,
            CurrentUserProvider currentUserProvider,
            DocumentAuthorizationService documentAuthorizationService,
            CollaborationSessionStore collaborationSessionStore,
            RedisCollaborationEventPublisher eventPublisher) {
        this.documentRepository = documentRepository;
        this.currentUserProvider = currentUserProvider;
        this.documentAuthorizationService = documentAuthorizationService;
        this.collaborationSessionStore = collaborationSessionStore;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public PresenceEventResponse publishPresence(UUID documentId, PresenceUpdateRequest request, SimpMessageHeaderAccessor headerAccessor) {
        if (request == null || request.sessionId() == null || request.type() == null) {
            throw new InvalidPresenceUpdateException("Presence update requires sessionId and type");
        }

        User actor = currentUserProvider.requireCurrentUser(headerAccessor);
        Map<String, Object> sessionAttributes = headerAccessor != null ? headerAccessor.getSessionAttributes() : null;
        Document document = requireDocument(documentId);
        documentAuthorizationService.assertCanRead(document, actor);

        if (sessionAttributes != null && !CollaborationSessionTracking.isTracked(sessionAttributes, documentId, request.sessionId())) {
            throw new CollaborationSessionAccessDeniedException(documentId, request.sessionId(), actor.getId());
        }

        CollaborationSessionResponse session = collaborationSessionStore.findBySessionId(documentId, request.sessionId())
                .orElseThrow(() -> new CollaborationSessionNotFoundException(documentId, request.sessionId()));
        if (!session.userId().equals(actor.getId())) {
            throw new CollaborationSessionAccessDeniedException(documentId, request.sessionId(), actor.getId());
        }

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

    private Document requireDocument(UUID documentId) {
        return documentRepository.findDetailedById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }
}
