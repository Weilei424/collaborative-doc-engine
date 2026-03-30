package com.mwang.backend.service;

import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentCollaborator;
import com.mwang.backend.domain.DocumentPermission;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentCollaboratorRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.repositories.UserRepository;
import com.mwang.backend.service.exception.CollaboratorAlreadyExistsException;
import com.mwang.backend.service.exception.CollaboratorNotFoundException;
import com.mwang.backend.service.exception.InvalidCollaborationRequestException;
import com.mwang.backend.web.mappers.DocumentMapper;
import com.mwang.backend.web.model.DocumentCollaboratorSummary;
import com.mwang.backend.web.model.DocumentOwnerSummary;
import com.mwang.backend.web.model.DocumentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollaboratorManagementServiceTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentCollaboratorRepository collaboratorRepository;
    @Mock private UserRepository userRepository;
    @Mock private DocumentAuthorizationService authorizationService;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private DocumentMapper documentMapper;
    @Mock private CollaborationBroadcastService collaborationBroadcastService;

    private CollaboratorManagementServiceImpl service;
    private User owner;
    private User collaboratorUser;
    private Document document;
    private UUID documentId;
    private final MockHttpServletRequest httpRequest = new MockHttpServletRequest();

    @BeforeEach
    void setUp() {
        service = new CollaboratorManagementServiceImpl(
                documentRepository, collaboratorRepository, userRepository,
                authorizationService, currentUserProvider, documentMapper,
                collaborationBroadcastService);

        documentId = UUID.randomUUID();
        owner = User.builder().id(UUID.randomUUID()).username("owner").email("o@e.com").passwordHash("h").build();
        collaboratorUser = User.builder().id(UUID.randomUUID()).username("collab").email("c@e.com").passwordHash("h").build();
        document = Document.builder()
                .id(documentId)
                .title("Test Doc")
                .owner(owner)
                .visibility(DocumentVisibility.PRIVATE)
                .build();

        when(currentUserProvider.requireCurrentUser(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn(owner);
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
    }

    @Test
    void listCollaborators_delegatesAuthorizationAndReturnsSummaries() {
        document.addCollaborator(collaboratorUser, DocumentPermission.READ);

        List<DocumentCollaboratorSummary> result = service.listCollaborators(documentId, httpRequest);

        verify(authorizationService).assertCanRead(document, owner);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo(collaboratorUser.getId());
        assertThat(result.get(0).permission()).isEqualTo(DocumentPermission.READ);
    }

    @Test
    void addCollaborator_savesEntryAndReturnsSummary() {
        when(userRepository.findById(collaboratorUser.getId())).thenReturn(Optional.of(collaboratorUser));
        when(collaboratorRepository.existsByDocumentIdAndUserId(documentId, collaboratorUser.getId()))
                .thenReturn(false);

        DocumentCollaboratorSummary result =
                service.addCollaborator(documentId, collaboratorUser.getId(), DocumentPermission.WRITE, httpRequest);

        verify(authorizationService).assertCanAdmin(document, owner);
        verify(collaboratorRepository).save(any(DocumentCollaborator.class));
        assertThat(result.userId()).isEqualTo(collaboratorUser.getId());
        assertThat(result.permission()).isEqualTo(DocumentPermission.WRITE);
    }

    @Test
    void addCollaborator_throwsWhenAlreadyExists() {
        when(userRepository.findById(collaboratorUser.getId())).thenReturn(Optional.of(collaboratorUser));
        when(collaboratorRepository.existsByDocumentIdAndUserId(documentId, collaboratorUser.getId()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.addCollaborator(documentId, collaboratorUser.getId(), DocumentPermission.WRITE, httpRequest))
                .isInstanceOf(CollaboratorAlreadyExistsException.class);
    }

    @Test
    void addCollaborator_throwsWhenTargetIsOwner() {
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service.addCollaborator(documentId, owner.getId(), DocumentPermission.WRITE, httpRequest))
                .isInstanceOf(InvalidCollaborationRequestException.class);
    }

    @Test
    void updateCollaborator_updatesPermissionAndReturnsSummary() {
        DocumentCollaborator entry = DocumentCollaborator.builder()
                .id(UUID.randomUUID()).document(document).user(collaboratorUser)
                .permission(DocumentPermission.READ).build();
        when(collaboratorRepository.findByDocumentIdAndUserId(documentId, collaboratorUser.getId()))
                .thenReturn(Optional.of(entry));

        DocumentCollaboratorSummary result =
                service.updateCollaborator(documentId, collaboratorUser.getId(), DocumentPermission.WRITE, httpRequest);

        verify(authorizationService).assertCanAdmin(document, owner);
        verify(collaboratorRepository).save(entry);
        assertThat(result.permission()).isEqualTo(DocumentPermission.WRITE);
    }

    @Test
    void updateCollaborator_throwsWhenNotFound() {
        when(collaboratorRepository.findByDocumentIdAndUserId(documentId, collaboratorUser.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateCollaborator(documentId, collaboratorUser.getId(), DocumentPermission.WRITE, httpRequest))
                .isInstanceOf(CollaboratorNotFoundException.class);
    }

    @Test
    void removeCollaborator_deletesEntryWhenExists() {
        when(collaboratorRepository.existsByDocumentIdAndUserId(documentId, collaboratorUser.getId()))
                .thenReturn(true);

        service.removeCollaborator(documentId, collaboratorUser.getId(), httpRequest);

        verify(authorizationService).assertCanAdmin(document, owner);
        verify(collaboratorRepository).deleteByDocumentIdAndUserId(documentId, collaboratorUser.getId());
    }

    @Test
    void removeCollaborator_throwsWhenNotFound() {
        when(collaboratorRepository.existsByDocumentIdAndUserId(documentId, collaboratorUser.getId()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.removeCollaborator(documentId, collaboratorUser.getId(), httpRequest))
                .isInstanceOf(CollaboratorNotFoundException.class);
    }

    @Test
    void removeCollaborator_throwsWhenTargetIsOwner() {
        assertThatThrownBy(() -> service.removeCollaborator(documentId, owner.getId(), httpRequest))
                .isInstanceOf(InvalidCollaborationRequestException.class);
    }

    @Test
    void transferOwnership_updatesOwnerAddsPreviousOwnerAsAdmin() {
        document.addCollaborator(collaboratorUser, DocumentPermission.WRITE);

        Document refreshed = Document.builder()
                .id(documentId).title("Test Doc").owner(collaboratorUser)
                .visibility(DocumentVisibility.PRIVATE).build();
        when(documentRepository.findDetailedById(documentId))
                .thenReturn(Optional.of(document))
                .thenReturn(Optional.of(refreshed));

        DocumentResponse expectedResponse = new DocumentResponse(
                documentId, "Test Doc", null, DocumentVisibility.PRIVATE, 0L, null, null,
                new DocumentOwnerSummary(collaboratorUser.getId(), collaboratorUser.getUsername()),
                List.of(), "OWNER");
        when(documentMapper.toResponse(refreshed, "OWNER")).thenReturn(expectedResponse);

        DocumentResponse result = service.transferOwnership(documentId, collaboratorUser.getId(), httpRequest);

        verify(authorizationService).assertOwner(document, owner);
        verify(documentRepository).save(document);
        assertThat(document.getOwner()).isEqualTo(collaboratorUser);
        assertThat(result).isEqualTo(expectedResponse);
    }

    @Test
    void transferOwnership_throwsWhenTargetNotACollaborator() {
        assertThatThrownBy(() -> service.transferOwnership(documentId, collaboratorUser.getId(), httpRequest))
                .isInstanceOf(CollaboratorNotFoundException.class);
    }

    @Test
    void removeCollaborator_publishesAccessRevokedEvent() {
        when(collaboratorRepository.existsByDocumentIdAndUserId(documentId, collaboratorUser.getId()))
                .thenReturn(true);

        service.removeCollaborator(documentId, collaboratorUser.getId(), httpRequest);

        verify(collaborationBroadcastService).broadcastAccessRevoked(documentId, collaboratorUser.getId());
    }

    @Test
    void transferOwnership_publishesAccessRevokedEventForOldOwner() {
        UUID oldOwnerId = owner.getId();
        document.addCollaborator(collaboratorUser, DocumentPermission.WRITE);

        Document refreshed = Document.builder()
                .id(documentId).title("Test Doc").owner(collaboratorUser)
                .visibility(DocumentVisibility.PRIVATE).build();
        when(documentRepository.findDetailedById(documentId))
                .thenReturn(Optional.of(document))
                .thenReturn(Optional.of(refreshed));
        when(documentMapper.toResponse(any(Document.class), any(String.class))).thenReturn(
                new DocumentResponse(documentId, "Test Doc", null, DocumentVisibility.PRIVATE,
                        0L, null, null,
                        new DocumentOwnerSummary(collaboratorUser.getId(), "collab"),
                        List.of(), "OWNER"));

        service.transferOwnership(documentId, collaboratorUser.getId(), httpRequest);

        verify(collaborationBroadcastService).broadcastAccessRevoked(documentId, oldOwnerId);
    }
}
