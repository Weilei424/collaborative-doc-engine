package com.mwang.backend.service;

import com.mwang.backend.collaboration.CollaborationSessionStore;
import com.mwang.backend.collaboration.RedisCollaborationEventPublisher;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.service.exception.CollaborationSessionAccessDeniedException;
import com.mwang.backend.service.exception.CollaborationSessionNotFoundException;
import com.mwang.backend.service.exception.DocumentNotFoundException;
import com.mwang.backend.web.model.CollaborationSessionResponse;
import com.mwang.backend.web.model.CollaborationSessionSnapshot;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CollaborationSessionServiceImpl implements CollaborationSessionService {

    private final DocumentRepository documentRepository;
    private final CurrentUserProvider currentUserProvider;
    private final DocumentAuthorizationService documentAuthorizationService;
    private final CollaborationSessionStore collaborationSessionStore;
    private final RedisCollaborationEventPublisher eventPublisher;

    public CollaborationSessionServiceImpl(
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
    public CollaborationSessionSnapshot join(UUID documentId) {
        return join(documentId, (SimpMessageHeaderAccessor) null);
    }

    @Override
    public CollaborationSessionSnapshot join(UUID documentId, SimpMessageHeaderAccessor headerAccessor) {
        User actor = currentUserProvider.requireCurrentUser(headerAccessor);
        Map<String, Object> sessionAttributes = headerAccessor != null ? headerAccessor.getSessionAttributes() : null;
        Document document = requireDocument(documentId);
        documentAuthorizationService.assertCanRead(document, actor);

        Instant now = Instant.now();
        CollaborationSessionResponse session = new CollaborationSessionResponse(
                UUID.randomUUID(),
                documentId,
                actor.getId(),
                actor.getUsername(),
                now,
                now
        );
        collaborationSessionStore.save(session);
        CollaborationSessionTracking.track(sessionAttributes, session);

        CollaborationSessionSnapshot snapshot = snapshotFor(documentId, session);
        eventPublisher.publishSessionSnapshot(documentId, snapshot);
        return snapshot;
    }

    @Override
    public CollaborationSessionSnapshot leave(UUID documentId, UUID sessionId) {
        return leave(documentId, sessionId, (SimpMessageHeaderAccessor) null);
    }

    @Override
    public CollaborationSessionSnapshot leave(UUID documentId, UUID sessionId, SimpMessageHeaderAccessor headerAccessor) {
        User actor = currentUserProvider.requireCurrentUser(headerAccessor);
        Map<String, Object> sessionAttributes = headerAccessor != null ? headerAccessor.getSessionAttributes() : null;
        Document document = requireDocument(documentId);
        documentAuthorizationService.assertCanRead(document, actor);

        if (sessionAttributes != null && !CollaborationSessionTracking.isTracked(sessionAttributes, documentId, sessionId)) {
            throw new CollaborationSessionAccessDeniedException(documentId, sessionId, actor.getId());
        }

        CollaborationSessionResponse session = collaborationSessionStore.findBySessionId(documentId, sessionId)
                .orElseThrow(() -> new CollaborationSessionNotFoundException(documentId, sessionId));
        if (!session.userId().equals(actor.getId())) {
            throw new CollaborationSessionAccessDeniedException(documentId, sessionId, actor.getId());
        }

        collaborationSessionStore.remove(documentId, sessionId);
        CollaborationSessionTracking.untrack(sessionAttributes, documentId, sessionId);
        CollaborationSessionSnapshot snapshot = snapshotFor(documentId, null);
        eventPublisher.publishSessionSnapshot(documentId, snapshot);
        return snapshot;
    }

    @Override
    public List<CollaborationSessionSnapshot> cleanupDisconnectedSessions(Map<String, Object> sessionAttributes) {
        List<CollaborationSessionTracking.TrackedSessionRef> trackedSessions = CollaborationSessionTracking.trackedSessions(sessionAttributes);
        if (trackedSessions.isEmpty()) {
            return List.of();
        }

        Map<UUID, Set<UUID>> sessionsByDocument = new LinkedHashMap<>();
        for (CollaborationSessionTracking.TrackedSessionRef trackedSession : trackedSessions) {
            sessionsByDocument
                    .computeIfAbsent(trackedSession.documentId(), ignored -> new LinkedHashSet<>())
                    .add(trackedSession.sessionId());
        }

        List<CollaborationSessionSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<UUID, Set<UUID>> entry : sessionsByDocument.entrySet()) {
            UUID documentId = entry.getKey();
            boolean removedAny = false;
            for (UUID sessionId : entry.getValue()) {
                if (collaborationSessionStore.findBySessionId(documentId, sessionId).isPresent()) {
                    collaborationSessionStore.remove(documentId, sessionId);
                    removedAny = true;
                }
            }
            if (!removedAny) {
                continue;
            }

            CollaborationSessionSnapshot snapshot = snapshotFor(documentId, null);
            eventPublisher.publishSessionSnapshot(documentId, snapshot);
            snapshots.add(snapshot);
        }

        CollaborationSessionTracking.clear(sessionAttributes);
        return snapshots;
    }

    private CollaborationSessionSnapshot snapshotFor(UUID documentId, CollaborationSessionResponse newlyCreatedSession) {
        List<CollaborationSessionResponse> sessions = new ArrayList<>(collaborationSessionStore.findByDocumentId(documentId));
        if (newlyCreatedSession != null && sessions.stream().noneMatch(existing -> existing.sessionId().equals(newlyCreatedSession.sessionId()))) {
            sessions.add(newlyCreatedSession);
        }
        sessions.sort(Comparator.comparing(CollaborationSessionResponse::joinedAt));
        return new CollaborationSessionSnapshot(documentId, sessions);
    }

    private Document requireDocument(UUID documentId) {
        return documentRepository.findDetailedById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }
}
