package com.mwang.backend.service;

import com.mwang.backend.collaboration.CollaborationSessionStore;
import com.mwang.backend.collaboration.RedisCollaborationEventPublisher;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
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

        when(currentUserProvider.requireCurrentUser()).thenReturn(actor);
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        doNothing().when(documentAuthorizationService).assertCanRead(document, actor);
        when(collaborationSessionStore.findByDocumentId(documentId)).thenReturn(List.of());

        var snapshot = sessionService.join(documentId, null);

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

        when(currentUserProvider.requireCurrentUser()).thenReturn(actor);
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        doNothing().when(documentAuthorizationService).assertCanRead(document, actor);
        when(collaborationSessionStore.findByDocumentId(documentId)).thenReturn(List.of());

        var snapshot = sessionService.leave(documentId, sessionId);

        verify(collaborationSessionStore).remove(documentId, sessionId);
        verify(eventPublisher).publishSessionSnapshot(documentId, snapshot);
        assertThat(snapshot.documentId()).isEqualTo(documentId);
        assertThat(snapshot.sessions()).isEmpty();
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
