package com.mwang.backend.service;

import com.mwang.backend.collaboration.CollaborationSessionStore;
import com.mwang.backend.collaboration.RedisCollaborationEventPublisher;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.service.exception.CollaborationSessionAccessDeniedException;
import com.mwang.backend.service.exception.CollaborationSessionNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollaborationSessionServiceTest {

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
    private CollaborationSessionServiceImpl sessionService;

    @Test
    void joinCreatesAuthorizedSessionSnapshot() {
        UUID documentId = UUID.randomUUID();
        User actor = newUser("collab-user");
        Document document = newDocument(documentId, actor);

        when(currentUserProvider.requireCurrentUser(org.mockito.ArgumentMatchers.nullable(org.springframework.messaging.simp.SimpMessageHeaderAccessor.class))).thenReturn(actor);
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        doNothing().when(documentAuthorizationService).assertCanRead(document, actor);
        when(collaborationSessionStore.findByDocumentId(documentId)).thenReturn(List.of());

        var snapshot = sessionService.join(documentId);

        verify(documentAuthorizationService).assertCanRead(document, actor);
        ArgumentCaptor<com.mwang.backend.web.model.CollaborationSessionResponse> captor = ArgumentCaptor.forClass(com.mwang.backend.web.model.CollaborationSessionResponse.class);
        verify(collaborationSessionStore).save(captor.capture());
        verify(eventPublisher).publishSessionSnapshot(documentId, snapshot);
        assertThat(captor.getValue().documentId()).isEqualTo(documentId);
        assertThat(captor.getValue().userId()).isEqualTo(actor.getId());
        assertThat(captor.getValue().username()).isEqualTo(actor.getUsername());
        assertThat(snapshot.documentId()).isEqualTo(documentId);
        assertThat(snapshot.sessions()).hasSize(1);
    }

    @Test
    void leaveRemovesExistingSessionFromMembershipSnapshot() {
        UUID documentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        User actor = newUser("session-owner");
        Document document = newDocument(documentId, actor);
        com.mwang.backend.web.model.CollaborationSessionResponse ownedSession = new com.mwang.backend.web.model.CollaborationSessionResponse(
                sessionId,
                documentId,
                actor.getId(),
                actor.getUsername(),
                Instant.parse("2026-03-23T12:00:00Z"),
                Instant.parse("2026-03-23T12:01:00Z")
        );

        when(currentUserProvider.requireCurrentUser(org.mockito.ArgumentMatchers.nullable(org.springframework.messaging.simp.SimpMessageHeaderAccessor.class))).thenReturn(actor);
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        doNothing().when(documentAuthorizationService).assertCanRead(document, actor);
        when(collaborationSessionStore.findBySessionId(documentId, sessionId)).thenReturn(Optional.of(ownedSession));
        when(collaborationSessionStore.findByDocumentId(documentId)).thenReturn(List.of());

        var snapshot = sessionService.leave(documentId, sessionId);

        verify(collaborationSessionStore).remove(documentId, sessionId);
        verify(eventPublisher).publishSessionSnapshot(documentId, snapshot);
        assertThat(snapshot.documentId()).isEqualTo(documentId);
        assertThat(snapshot.sessions()).isEmpty();
    }

    @Test
    void leaveRejectsUnknownSessionDeterministically() {
        UUID documentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        User actor = newUser("session-owner");
        Document document = newDocument(documentId, actor);

        when(currentUserProvider.requireCurrentUser(org.mockito.ArgumentMatchers.nullable(org.springframework.messaging.simp.SimpMessageHeaderAccessor.class))).thenReturn(actor);
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        doNothing().when(documentAuthorizationService).assertCanRead(document, actor);
        when(collaborationSessionStore.findBySessionId(documentId, sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionService.leave(documentId, sessionId))
                .isInstanceOf(CollaborationSessionNotFoundException.class);

        verify(collaborationSessionStore, never()).remove(documentId, sessionId);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void leaveRejectsRemovingAnotherUsersSession() {
        UUID documentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        User actor = newUser("session-owner");
        User otherUser = newUser("other-user");
        Document document = newDocument(documentId, actor);
        com.mwang.backend.web.model.CollaborationSessionResponse otherUsersSession = new com.mwang.backend.web.model.CollaborationSessionResponse(
                sessionId,
                documentId,
                otherUser.getId(),
                otherUser.getUsername(),
                Instant.parse("2026-03-23T12:00:00Z"),
                Instant.parse("2026-03-23T12:01:00Z")
        );

        when(currentUserProvider.requireCurrentUser(org.mockito.ArgumentMatchers.nullable(org.springframework.messaging.simp.SimpMessageHeaderAccessor.class))).thenReturn(actor);
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        doNothing().when(documentAuthorizationService).assertCanRead(document, actor);
        when(collaborationSessionStore.findBySessionId(documentId, sessionId)).thenReturn(Optional.of(otherUsersSession));

        assertThatThrownBy(() -> sessionService.leave(documentId, sessionId))
                .isInstanceOf(CollaborationSessionAccessDeniedException.class);

        verify(collaborationSessionStore, never()).remove(documentId, sessionId);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void leaveRejectsSessionOwnedByAnotherSocketOfSameUser() {
        UUID documentId = UUID.randomUUID();
        User actor = newUser("session-owner");
        Document document = newDocument(documentId, actor);
        HashMap<String, Object> ownerSessionAttributes = new HashMap<>();
        HashMap<String, Object> otherConnectionAttributes = new HashMap<>();

        SimpMessageHeaderAccessor ownerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(ownerAccessor.getSessionAttributes()).thenReturn(ownerSessionAttributes);

        SimpMessageHeaderAccessor otherAccessor = mock(SimpMessageHeaderAccessor.class);
        when(otherAccessor.getSessionAttributes()).thenReturn(otherConnectionAttributes);

        when(currentUserProvider.requireCurrentUser(org.mockito.ArgumentMatchers.nullable(org.springframework.messaging.simp.SimpMessageHeaderAccessor.class))).thenReturn(actor);
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        doNothing().when(documentAuthorizationService).assertCanRead(document, actor);
        when(collaborationSessionStore.findByDocumentId(documentId)).thenReturn(List.of());

        var joinSnapshot = sessionService.join(documentId, ownerAccessor);
        var ownedSession = joinSnapshot.sessions().get(0);

        assertThatThrownBy(() -> sessionService.leave(documentId, ownedSession.sessionId(), otherAccessor))
                .isInstanceOf(CollaborationSessionAccessDeniedException.class);

        verify(collaborationSessionStore, never()).remove(documentId, ownedSession.sessionId());
    }

    @Test
    void cleanupDisconnectedSessionsRemovesTrackedSessionsAndPublishesFreshSnapshots() {
        UUID documentId = UUID.randomUUID();
        User actor = newUser("socket-owner");
        Document document = newDocument(documentId, actor);
        HashMap<String, Object> sessionAttributes = new HashMap<>();

        SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);
        when(accessor.getSessionAttributes()).thenReturn(sessionAttributes);

        when(currentUserProvider.requireCurrentUser(org.mockito.ArgumentMatchers.nullable(org.springframework.messaging.simp.SimpMessageHeaderAccessor.class))).thenReturn(actor);
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        doNothing().when(documentAuthorizationService).assertCanRead(document, actor);
        when(collaborationSessionStore.findByDocumentId(documentId)).thenReturn(List.of(), List.of());

        var joinSnapshot = sessionService.join(documentId, accessor);
        UUID createdSessionId = joinSnapshot.sessions().get(0).sessionId();
        when(collaborationSessionStore.findBySessionId(documentId, createdSessionId)).thenReturn(Optional.of(joinSnapshot.sessions().get(0)));

        var cleanupSnapshots = sessionService.cleanupDisconnectedSessions(sessionAttributes);

        assertThat(cleanupSnapshots)
                .singleElement()
                .satisfies(snapshot -> {
                    assertThat(snapshot.documentId()).isEqualTo(documentId);
                    assertThat(snapshot.sessions()).isEmpty();
                });
        verify(collaborationSessionStore).remove(documentId, createdSessionId);
        ArgumentCaptor<com.mwang.backend.web.model.CollaborationSessionSnapshot> snapshotCaptor =
                ArgumentCaptor.forClass(com.mwang.backend.web.model.CollaborationSessionSnapshot.class);
        verify(eventPublisher, times(2)).publishSessionSnapshot(eq(documentId), snapshotCaptor.capture());
        assertThat(snapshotCaptor.getAllValues().get(1).sessions()).isEmpty();
        assertThat(sessionAttributes).isEmpty();
    }

    @Test
    void cleanupDisconnectedSessionsSkipsSessionsAlreadyLeftExplicitly() {
        UUID documentId = UUID.randomUUID();
        User actor = newUser("socket-owner");
        Document document = newDocument(documentId, actor);
        HashMap<String, Object> sessionAttributes = new HashMap<>();

        SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);
        when(accessor.getSessionAttributes()).thenReturn(sessionAttributes);

        when(currentUserProvider.requireCurrentUser(org.mockito.ArgumentMatchers.nullable(org.springframework.messaging.simp.SimpMessageHeaderAccessor.class))).thenReturn(actor);
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        doNothing().when(documentAuthorizationService).assertCanRead(document, actor);
        when(collaborationSessionStore.findByDocumentId(documentId)).thenReturn(List.of(), List.of());

        var joinSnapshot = sessionService.join(documentId, accessor);
        UUID createdSessionId = joinSnapshot.sessions().get(0).sessionId();
        com.mwang.backend.web.model.CollaborationSessionResponse ownedSession = new com.mwang.backend.web.model.CollaborationSessionResponse(
                createdSessionId,
                documentId,
                actor.getId(),
                actor.getUsername(),
                Instant.parse("2026-03-23T12:00:00Z"),
                Instant.parse("2026-03-23T12:01:00Z")
        );
        when(collaborationSessionStore.findBySessionId(documentId, createdSessionId)).thenReturn(Optional.of(ownedSession), Optional.empty());

        sessionService.leave(documentId, createdSessionId, accessor);

        assertThat(sessionService.cleanupDisconnectedSessions(sessionAttributes)).isEmpty();
        verify(collaborationSessionStore, times(1)).remove(documentId, createdSessionId);
        verify(eventPublisher, times(2)).publishSessionSnapshot(eq(documentId), any());
        assertThat(sessionAttributes).isEmpty();
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
