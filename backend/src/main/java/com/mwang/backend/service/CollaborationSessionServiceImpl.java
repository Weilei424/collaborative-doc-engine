package com.mwang.backend.service;

import com.mwang.backend.collaboration.CollaborationSessionStore;
import com.mwang.backend.collaboration.RedisCollaborationEventPublisher;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.service.exception.DocumentNotFoundException;
import com.mwang.backend.web.model.CollaborationSessionResponse;
import com.mwang.backend.web.model.CollaborationSessionSnapshot;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    public CollaborationSessionSnapshot join(UUID documentId, UUID clientSessionHint) {
        User actor = currentUserProvider.requireCurrentUser();
        Document document = requireDocument(documentId);
        documentAuthorizationService.assertCanRead(document, actor);

        Instant now = Instant.now();
        CollaborationSessionResponse session = new CollaborationSessionResponse(
                clientSessionHint != null ? clientSessionHint : UUID.randomUUID(),
                documentId,
                actor.getId(),
                actor.getUsername(),
                now,
                now
        );
        collaborationSessionStore.save(session);

        CollaborationSessionSnapshot snapshot = snapshotFor(documentId, session);
        eventPublisher.publishSessionSnapshot(documentId, snapshot);
        return snapshot;
    }

    @Override
    public CollaborationSessionSnapshot leave(UUID documentId, UUID sessionId) {
        User actor = currentUserProvider.requireCurrentUser();
        Document document = requireDocument(documentId);
        documentAuthorizationService.assertCanRead(document, actor);

        collaborationSessionStore.remove(documentId, sessionId);
        CollaborationSessionSnapshot snapshot = snapshotFor(documentId, null);
        eventPublisher.publishSessionSnapshot(documentId, snapshot);
        return snapshot;
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
