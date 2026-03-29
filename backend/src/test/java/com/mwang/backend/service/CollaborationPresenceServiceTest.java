package com.mwang.backend.service;

import com.mwang.backend.collaboration.CollaborationSessionStore;
import com.mwang.backend.collaboration.RedisCollaborationEventPublisher;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.service.exception.CollaborationSessionAccessDeniedException;
import com.mwang.backend.service.exception.CollaborationSessionNotFoundException;
import com.mwang.backend.service.exception.DocumentAccessDeniedException;
import com.mwang.backend.web.model.CollaborationSessionResponse;
import com.mwang.backend.web.model.PresenceType;
import com.mwang.backend.web.model.PresenceUpdateRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollaborationPresenceServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private DocumentAuthorizationService documentAuthorizationService;

    @Mock
    private CollaborationSessionStore collaborationSessionStore;

    @Mock
    private RedisCollaborationEventPublisher eventPublisher;

    @InjectMocks
    private CollaborationPresenceServiceImpl presenceService;

    @Test
    void publishPresenceRequiresAnActiveSession() {
        UUID documentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        User actor = newUser("tester");
        Document document = newDocument(documentId, actor);
        PresenceUpdateRequest request = new PresenceUpdateRequest(sessionId, PresenceType.TYPING, Map.of("typing", true));
        SimpMessageHeaderAccessor accessor = trackedAccessor(documentId, sessionId);

        when(currentUserProvider.requireCurrentUser(any(org.springframework.messaging.simp.SimpMessageHeaderAccessor.class))).thenReturn(actor);
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        doNothing().when(documentAuthorizationService).assertCanRead(document, actor);
        when(collaborationSessionStore.findBySessionId(documentId, sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> presenceService.publishPresence(documentId, request, accessor))
                .isInstanceOf(CollaborationSessionNotFoundException.class);
    }

    @Test
    void publishPresenceRequiresDocumentReadAccess() {
        UUID documentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        User actor = newUser("tester");
        Document document = newDocument(documentId, actor);
        PresenceUpdateRequest request = new PresenceUpdateRequest(sessionId, PresenceType.TYPING, Map.of("typing", true));
        DocumentAccessDeniedException accessDeniedException = new DocumentAccessDeniedException(documentId, actor.getId());
        SimpMessageHeaderAccessor accessor = trackedAccessor(documentId, sessionId);

        when(currentUserProvider.requireCurrentUser(any(org.springframework.messaging.simp.SimpMessageHeaderAccessor.class))).thenReturn(actor);
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        doThrow(accessDeniedException).when(documentAuthorizationService).assertCanRead(document, actor);

        assertThatThrownBy(() -> presenceService.publishPresence(documentId, request, accessor))
                .isEqualTo(accessDeniedException);

        verifyNoInteractions(collaborationSessionStore, eventPublisher);
    }

    @Test
    void publishPresenceRejectsSpoofedSession() {
        UUID documentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        User actor = newUser("tester");
        User otherUser = newUser("other-user");
        Document document = newDocument(documentId, actor);
        PresenceUpdateRequest request = new PresenceUpdateRequest(sessionId, PresenceType.TYPING, Map.of("typing", true));
        CollaborationSessionResponse session = new CollaborationSessionResponse(
                sessionId,
                documentId,
                otherUser.getId(),
                otherUser.getUsername(),
                Instant.parse("2026-03-23T12:00:00Z"),
                Instant.parse("2026-03-23T12:01:00Z")
        );
        SimpMessageHeaderAccessor accessor = trackedAccessor(documentId, sessionId);

        when(currentUserProvider.requireCurrentUser(any(org.springframework.messaging.simp.SimpMessageHeaderAccessor.class))).thenReturn(actor);
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        doNothing().when(documentAuthorizationService).assertCanRead(document, actor);
        when(collaborationSessionStore.findBySessionId(documentId, sessionId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> presenceService.publishPresence(documentId, request, accessor))
                .isInstanceOf(CollaborationSessionAccessDeniedException.class);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void publishPresenceRejectsSessionOwnedByAnotherSocketOfSameUser() {
        UUID documentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        User actor = newUser("tester");
        Document document = newDocument(documentId, actor);
        PresenceUpdateRequest request = new PresenceUpdateRequest(sessionId, PresenceType.TYPING, Map.of("typing", true));
        // Empty session attributes — session not tracked
        SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);
        when(accessor.getSessionAttributes()).thenReturn(Map.of());

        when(currentUserProvider.requireCurrentUser(any(org.springframework.messaging.simp.SimpMessageHeaderAccessor.class))).thenReturn(actor);
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        doNothing().when(documentAuthorizationService).assertCanRead(document, actor);

        assertThatThrownBy(() -> presenceService.publishPresence(documentId, request, accessor))
                .isInstanceOf(CollaborationSessionAccessDeniedException.class);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void publishPresenceBuildsCanonicalEventForActiveSession() {
        UUID documentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        User actor = newUser("tester");
        Document document = newDocument(documentId, actor);
        PresenceUpdateRequest request = new PresenceUpdateRequest(sessionId, PresenceType.CURSOR_POSITION, Map.of("line", 4));
        CollaborationSessionResponse session = new CollaborationSessionResponse(
                sessionId,
                documentId,
                actor.getId(),
                actor.getUsername(),
                Instant.parse("2026-03-23T12:00:00Z"),
                Instant.parse("2026-03-23T12:01:00Z")
        );
        SimpMessageHeaderAccessor accessor = trackedAccessor(documentId, sessionId);

        when(currentUserProvider.requireCurrentUser(any(org.springframework.messaging.simp.SimpMessageHeaderAccessor.class))).thenReturn(actor);
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        doNothing().when(documentAuthorizationService).assertCanRead(document, actor);
        when(collaborationSessionStore.findBySessionId(documentId, sessionId)).thenReturn(Optional.of(session));

        var event = presenceService.publishPresence(documentId, request, accessor);

        assertThat(event.documentId()).isEqualTo(documentId);
        assertThat(event.sessionId()).isEqualTo(sessionId);
        assertThat(event.userId()).isEqualTo(actor.getId());
        assertThat(event.username()).isEqualTo(actor.getUsername());
        assertThat(event.type()).isEqualTo(PresenceType.CURSOR_POSITION);
        assertThat(event.payload()).containsEntry("line", 4);
        verify(eventPublisher).publishPresenceEvent(event);
    }

    private SimpMessageHeaderAccessor trackedAccessor(UUID documentId, UUID sessionId) {
        SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);
        Map<String, Object> attrs = Map.of(
                CollaborationSessionTracking.class.getName() + ".trackedSessions",
                List.of(new CollaborationSessionTracking.TrackedSessionRef(documentId, sessionId))
        );
        when(accessor.getSessionAttributes()).thenReturn(attrs);
        return accessor;
    }

    private User newUser(String username) {
        return User.builder()
                .id(UUID.randomUUID())
                .username(username)
                .email(username + "@example.com")
                .passwordHash("secret")
                .build();
    }

    private Document newDocument(UUID documentId, User owner) {
        Document document = Document.builder()
                .id(documentId)
                .title("Doc")
                .content("Hello")
                .visibility(DocumentVisibility.PRIVATE)
                .owner(owner)
                .currentVersion(0L)
                .build();
        document.setCreatedAt(Instant.parse("2026-03-23T12:00:00Z"));
        document.setUpdatedAt(Instant.parse("2026-03-23T12:00:00Z"));
        return document;
    }
}
