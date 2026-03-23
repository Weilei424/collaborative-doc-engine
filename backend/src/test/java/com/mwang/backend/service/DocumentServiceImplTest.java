package com.mwang.backend.service;

import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentPermission;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.web.controller.DocumentListScope;
import com.mwang.backend.web.mappers.DocumentMapper;
import com.mwang.backend.web.model.CreateDocumentRequest;
import com.mwang.backend.web.model.DocumentCollaboratorSummary;
import com.mwang.backend.web.model.DocumentOwnerSummary;
import com.mwang.backend.web.model.DocumentPagedList;
import com.mwang.backend.web.model.DocumentResponse;
import com.mwang.backend.web.model.UpdateDocumentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private DocumentAuthorizationService documentAuthorizationService;

    @Mock
    private DocumentMapper documentMapper;

    @InjectMocks
    private DocumentServiceImpl service;

    @Test
    void createAssignsCurrentActorAsOwnerAndReturnsOwnerPermission() {
        User actor = newUser("owner-one");
        CreateDocumentRequest request = new CreateDocumentRequest("Doc", "Hello", DocumentVisibility.PRIVATE);
        Document saved = newDocument(actor, "Doc", DocumentVisibility.PRIVATE);
        DocumentResponse mapped = response(saved.getId(), actor.getId(), actor.getUsername(), "OWNER");

        when(currentUserProvider.requireCurrentUser()).thenReturn(actor);
        when(documentRepository.save(any(Document.class))).thenReturn(saved);
        when(documentMapper.toResponse(saved, "OWNER")).thenReturn(mapped);

        DocumentResponse created = service.create(request);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        Document toSave = captor.getValue();
        assertThat(toSave.getOwner()).isEqualTo(actor);
        assertThat(toSave.getVisibility()).isEqualTo(DocumentVisibility.PRIVATE);
        assertThat(created.owner().id()).isEqualTo(actor.getId());
        assertThat(created.currentUserPermission()).isEqualTo("OWNER");
    }

    @Test
    void getByIdAuthorizesReadAndMapsEffectivePermission() {
        User actor = newUser("reader-one");
        User owner = newUser("owner-two");
        Document document = newDocument(owner, "Readable doc", DocumentVisibility.SHARED);
        DocumentResponse mapped = response(document.getId(), owner.getId(), owner.getUsername(), "READ");

        when(currentUserProvider.requireCurrentUser()).thenReturn(actor);
        when(documentRepository.findDetailedById(document.getId())).thenReturn(Optional.of(document));
        doNothing().when(documentAuthorizationService).assertCanRead(document, actor);
        when(documentAuthorizationService.resolveEffectivePermission(document, actor)).thenReturn("READ");
        when(documentMapper.toResponse(document, "READ")).thenReturn(mapped);

        DocumentResponse found = service.getById(document.getId());

        verify(documentAuthorizationService).assertCanRead(document, actor);
        assertThat(found.currentUserPermission()).isEqualTo("READ");
    }

    @Test
    void listReturnsOwnedDocumentsWithinRequestedScope() {
        User actor = newUser("owner-scope");
        Document owned = newDocument(actor, "Doc A", DocumentVisibility.PRIVATE);
        DocumentResponse mapped = response(owned.getId(), actor.getId(), actor.getUsername(), "OWNER");
        PageRequest pageable = PageRequest.of(0, 20);

        when(currentUserProvider.requireCurrentUser()).thenReturn(actor);
        when(documentRepository.findOwnedByUserId(actor.getId(), "Doc", pageable))
                .thenReturn(new PageImpl<>(List.of(owned), pageable, 1));
        when(documentRepository.findAllDetailedByIdIn(List.of(owned.getId()))).thenReturn(List.of(owned));
        when(documentAuthorizationService.resolveEffectivePermission(owned, actor)).thenReturn("OWNER");
        when(documentMapper.toResponse(owned, "OWNER")).thenReturn(mapped);

        DocumentPagedList result = service.list(DocumentListScope.OWNED, "Doc", pageable);

        assertThat(result.items()).containsExactly(mapped);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
    }

    @Test
    void listReturnsSharedDocumentsWithinRequestedScope() {
        User actor = newUser("shared-scope");
        User owner = newUser("shared-owner");
        Document shared = newDocument(owner, "Shared Doc", DocumentVisibility.SHARED);
        DocumentResponse mapped = response(shared.getId(), owner.getId(), owner.getUsername(), "WRITE");
        PageRequest pageable = PageRequest.of(0, 20);

        when(currentUserProvider.requireCurrentUser()).thenReturn(actor);
        when(documentRepository.findSharedWithUserId(actor.getId(), null, pageable))
                .thenReturn(new PageImpl<>(List.of(shared), pageable, 1));
        when(documentRepository.findAllDetailedByIdIn(List.of(shared.getId()))).thenReturn(List.of(shared));
        when(documentAuthorizationService.resolveEffectivePermission(shared, actor)).thenReturn("WRITE");
        when(documentMapper.toResponse(shared, "WRITE")).thenReturn(mapped);

        DocumentPagedList result = service.list(DocumentListScope.SHARED, null, pageable);

        assertThat(result.items()).containsExactly(mapped);
        assertThat(result.items().get(0).currentUserPermission()).isEqualTo("WRITE");
    }

    @Test
    void listReturnsPublicDocumentsWithinRequestedScope() {
        User actor = newUser("public-scope");
        User owner = newUser("public-owner");
        Document publicDocument = newDocument(owner, "Public Doc", DocumentVisibility.PUBLIC);
        DocumentResponse mapped = response(publicDocument.getId(), owner.getId(), owner.getUsername(), "READ");
        PageRequest pageable = PageRequest.of(0, 20);

        when(currentUserProvider.requireCurrentUser()).thenReturn(actor);
        when(documentRepository.findPublicDocuments("pub", pageable))
                .thenReturn(new PageImpl<>(List.of(publicDocument), pageable, 1));
        when(documentRepository.findAllDetailedByIdIn(List.of(publicDocument.getId()))).thenReturn(List.of(publicDocument));
        when(documentAuthorizationService.resolveEffectivePermission(publicDocument, actor)).thenReturn("READ");
        when(documentMapper.toResponse(publicDocument, "READ")).thenReturn(mapped);

        DocumentPagedList result = service.list(DocumentListScope.PUBLIC, "pub", pageable);

        assertThat(result.items()).containsExactly(mapped);
        assertThat(result.items().get(0).currentUserPermission()).isEqualTo("READ");
    }

    @Test
    void listReturnsAccessibleDocumentsAcrossScopes() {
        User actor = newUser("accessible-scope");
        User owner = newUser("accessible-owner");
        Document accessible = newDocument(owner, "Accessible Doc", DocumentVisibility.PUBLIC);
        DocumentResponse mapped = response(accessible.getId(), owner.getId(), owner.getUsername(), "READ");
        PageRequest pageable = PageRequest.of(0, 20);

        when(currentUserProvider.requireCurrentUser()).thenReturn(actor);
        when(documentRepository.findAccessibleByUserId(actor.getId(), null, pageable))
                .thenReturn(new PageImpl<>(List.of(accessible), pageable, 1));
        when(documentRepository.findAllDetailedByIdIn(List.of(accessible.getId()))).thenReturn(List.of(accessible));
        when(documentAuthorizationService.resolveEffectivePermission(accessible, actor)).thenReturn("READ");
        when(documentMapper.toResponse(accessible, "READ")).thenReturn(mapped);

        DocumentPagedList result = service.list(DocumentListScope.ACCESSIBLE, null, pageable);

        assertThat(result.items()).containsExactly(mapped);
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void updateRequiresOwnerAndPersistsEditableFields() {
        User actor = newUser("owner-update");
        Document existing = newDocument(actor, "Before", DocumentVisibility.PRIVATE);
        UpdateDocumentRequest request = new UpdateDocumentRequest("After", "Updated", DocumentVisibility.PUBLIC);
        DocumentResponse mapped = response(existing.getId(), actor.getId(), actor.getUsername(), "OWNER");

        when(currentUserProvider.requireCurrentUser()).thenReturn(actor);
        when(documentRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        doNothing().when(documentAuthorizationService).assertOwner(existing, actor);
        when(documentRepository.save(existing)).thenReturn(existing);
        when(documentMapper.toResponse(existing, "OWNER")).thenReturn(mapped);

        DocumentResponse updated = service.update(existing.getId(), request);

        assertThat(existing.getTitle()).isEqualTo("After");
        assertThat(existing.getContent()).isEqualTo("Updated");
        assertThat(existing.getVisibility()).isEqualTo(DocumentVisibility.PUBLIC);
        assertThat(updated.currentUserPermission()).isEqualTo("OWNER");
    }

    @Test
    void deleteRequiresOwner() {
        User actor = newUser("owner-delete");
        Document document = newDocument(actor, "Delete me", DocumentVisibility.PRIVATE);

        when(currentUserProvider.requireCurrentUser()).thenReturn(actor);
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
        doNothing().when(documentAuthorizationService).assertOwner(document, actor);

        service.delete(document.getId());

        verify(documentAuthorizationService).assertOwner(document, actor);
        verify(documentRepository).delete(document);
    }

    private User newUser(String seed) {
        return User.builder()
                .id(UUID.randomUUID())
                .username(seed)
                .email(seed + "@example.com")
                .passwordHash("hashed-password")
                .build();
    }

    private Document newDocument(User owner, String title, DocumentVisibility visibility) {
        Document document = Document.builder()
                .id(UUID.randomUUID())
                .title(title)
                .content("initial")
                .owner(owner)
                .visibility(visibility)
                .currentVersion(0L)
                .build();
        document.setCreatedAt(Instant.parse("2026-03-22T12:00:00Z"));
        document.setUpdatedAt(Instant.parse("2026-03-22T12:05:00Z"));
        return document;
    }

    private DocumentResponse response(UUID documentId, UUID ownerId, String ownerUsername, String permission) {
        return new DocumentResponse(
                documentId,
                "Doc",
                "Hello",
                DocumentVisibility.PRIVATE,
                0L,
                Instant.parse("2026-03-22T12:00:00Z"),
                Instant.parse("2026-03-22T12:05:00Z"),
                new DocumentOwnerSummary(ownerId, ownerUsername),
                List.of(new DocumentCollaboratorSummary(UUID.randomUUID(), "collab", DocumentPermission.READ)),
                permission
        );
    }
}
