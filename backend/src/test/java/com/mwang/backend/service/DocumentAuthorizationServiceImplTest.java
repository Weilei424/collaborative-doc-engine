package com.mwang.backend.service;

import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentPermission;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.domain.User;
import com.mwang.backend.service.exception.DocumentAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentAuthorizationServiceImplTest {

    private DocumentAuthorizationServiceImpl authorizationService;
    private User owner;
    private Document document;

    @BeforeEach
    void setUp() {
        authorizationService = new DocumentAuthorizationServiceImpl();
        owner = User.builder()
                .id(UUID.randomUUID()).username("owner").email("o@e.com").passwordHash("h").build();
        document = Document.builder()
                .id(UUID.randomUUID())
                .title("Doc")
                .owner(owner)
                .visibility(DocumentVisibility.PRIVATE)
                .build();
    }

    @Test
    void assertCanAdmin_allowsOwner() {
        assertThatCode(() -> authorizationService.assertCanAdmin(document, owner))
                .doesNotThrowAnyException();
    }

    @Test
    void assertCanAdmin_allowsAdminCollaborator() {
        User admin = User.builder()
                .id(UUID.randomUUID()).username("admin").email("a@e.com").passwordHash("h").build();
        document.addCollaborator(admin, DocumentPermission.ADMIN);

        assertThatCode(() -> authorizationService.assertCanAdmin(document, admin))
                .doesNotThrowAnyException();
    }

    @Test
    void assertCanAdmin_deniesWriteCollaborator() {
        User writer = User.builder()
                .id(UUID.randomUUID()).username("writer").email("w@e.com").passwordHash("h").build();
        document.addCollaborator(writer, DocumentPermission.WRITE);

        assertThatThrownBy(() -> authorizationService.assertCanAdmin(document, writer))
                .isInstanceOf(DocumentAccessDeniedException.class);
    }

    @Test
    void assertCanAdmin_deniesReadCollaborator() {
        User reader = User.builder()
                .id(UUID.randomUUID()).username("reader").email("r@e.com").passwordHash("h").build();
        document.addCollaborator(reader, DocumentPermission.READ);

        assertThatThrownBy(() -> authorizationService.assertCanAdmin(document, reader))
                .isInstanceOf(DocumentAccessDeniedException.class);
    }

    @Test
    void assertCanAdmin_deniesUnrelatedUser() {
        User stranger = User.builder()
                .id(UUID.randomUUID()).username("stranger").email("s@e.com").passwordHash("h").build();

        assertThatThrownBy(() -> authorizationService.assertCanAdmin(document, stranger))
                .isInstanceOf(DocumentAccessDeniedException.class);
    }
}
