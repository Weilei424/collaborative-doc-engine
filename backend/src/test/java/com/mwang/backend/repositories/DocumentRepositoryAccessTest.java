package com.mwang.backend.repositories;

import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentCollaborator;
import com.mwang.backend.domain.DocumentPermission;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.domain.User;
import com.mwang.backend.testcontainers.AbstractRepositoryTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentRepositoryAccessTest extends AbstractRepositoryTest {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentCollaboratorRepository documentCollaboratorRepository;

    @Test
    void scopeQueriesReturnOwnedSharedPublicAndAccessibleDocuments() {
        User owner = userRepository.saveAndFlush(newUser("owner-phase2"));
        User collaborator = userRepository.saveAndFlush(newUser("collaborator-phase2"));

        Document owned = documentRepository.saveAndFlush(newDocument(owner, "Owned Alpha", DocumentVisibility.PRIVATE));
        Document shared = documentRepository.saveAndFlush(newDocument(owner, "Shared Beta", DocumentVisibility.SHARED));
        Document publicDoc = documentRepository.saveAndFlush(newDocument(owner, "Public Gamma", DocumentVisibility.PUBLIC));

        documentCollaboratorRepository.saveAndFlush(DocumentCollaborator.builder()
                .document(shared)
                .user(collaborator)
                .permission(DocumentPermission.READ)
                .build());

        assertThat(documentRepository.findOwnedByUserId(owner.getId(), null, PageRequest.of(0, 20)).getContent())
                .extracting(Document::getId)
                .containsExactlyInAnyOrder(owned.getId(), shared.getId(), publicDoc.getId());

        assertThat(documentRepository.findSharedWithUserId(collaborator.getId(), null, PageRequest.of(0, 20)).getContent())
                .extracting(Document::getId)
                .containsExactly(shared.getId());

        assertThat(documentRepository.findPublicDocuments(null, PageRequest.of(0, 20)).getContent())
                .extracting(Document::getId)
                .containsExactly(publicDoc.getId());

        assertThat(documentRepository.findAccessibleByUserId(collaborator.getId(), null, PageRequest.of(0, 20)).getContent())
                .extracting(Document::getId)
                .containsExactlyInAnyOrder(shared.getId(), publicDoc.getId());
    }

    @Test
    void scopeQueriesApplyTitleSearchWithinScope() {
        User owner = userRepository.saveAndFlush(newUser("owner-search"));
        User collaborator = userRepository.saveAndFlush(newUser("collaborator-search"));

        Document ownedMatch = documentRepository.saveAndFlush(newDocument(owner, "Spec Owned", DocumentVisibility.PRIVATE));
        documentRepository.saveAndFlush(newDocument(owner, "Roadmap Owned", DocumentVisibility.PRIVATE));
        Document sharedMatch = documentRepository.saveAndFlush(newDocument(owner, "Spec Notes", DocumentVisibility.SHARED));
        documentRepository.saveAndFlush(newDocument(owner, "Roadmap", DocumentVisibility.SHARED));
        Document publicMatch = documentRepository.saveAndFlush(newDocument(owner, "Spec Public", DocumentVisibility.PUBLIC));

        documentCollaboratorRepository.saveAndFlush(DocumentCollaborator.builder()
                .document(sharedMatch)
                .user(collaborator)
                .permission(DocumentPermission.WRITE)
                .build());

        assertThat(documentRepository.findOwnedByUserId(owner.getId(), "spec", PageRequest.of(0, 20)).getContent())
                .extracting(Document::getId)
                .containsExactlyInAnyOrder(ownedMatch.getId(), sharedMatch.getId(), publicMatch.getId());


        assertThat(documentRepository.findSharedWithUserId(collaborator.getId(), "spec", PageRequest.of(0, 20)).getContent())
                .extracting(Document::getId)
                .containsExactly(sharedMatch.getId());

        assertThat(documentRepository.findPublicDocuments("spec", PageRequest.of(0, 20)).getContent())
                .extracting(Document::getId)
                .containsExactly(publicMatch.getId());

        assertThat(documentRepository.findAccessibleByUserId(collaborator.getId(), "spec", PageRequest.of(0, 20)).getContent())
                .extracting(Document::getId)
                .containsExactlyInAnyOrder(sharedMatch.getId(), publicMatch.getId());
    }

    private User newUser(String seed) {
        return User.builder()
                .username(seed)
                .email(seed + "@example.com")
                .passwordHash("hashed-password")
                .build();
    }

    private Document newDocument(User owner, String title, DocumentVisibility visibility) {
        return Document.builder()
                .title(title)
                .content("content")
                .owner(owner)
                .visibility(visibility)
                .build();
    }
}


