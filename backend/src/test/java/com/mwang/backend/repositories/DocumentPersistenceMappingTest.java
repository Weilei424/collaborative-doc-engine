package com.mwang.backend.repositories;

import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentOperation;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class DocumentPersistenceMappingTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentOperationRepository documentOperationRepository;

    @Test
    void optimisticLockVersionAndCollaborationCurrentVersionStaySeparate() {
        User owner = userRepository.saveAndFlush(User.builder()
                .username("mapping-owner")
                .email("mapping-owner@example.com")
                .passwordHash("hashed-password")
                .build());

        Document document = documentRepository.saveAndFlush(Document.builder()
                .title("Mapping document")
                .content("before")
                .owner(owner)
                .visibility(DocumentVisibility.PRIVATE)
                .build());

        assertThat(document.getVersion()).isEqualTo(0L);
        assertThat(document.getCurrentVersion()).isEqualTo(0L);

        document.setContent("after-content-only");
        documentRepository.saveAndFlush(document);

        assertThat(document.getVersion()).isEqualTo(1L);
        assertThat(document.getCurrentVersion()).isEqualTo(0L);

        document.setCurrentVersion(1L);
        documentRepository.saveAndFlush(document);

        assertThat(document.getVersion()).isEqualTo(2L);
        assertThat(document.getCurrentVersion()).isEqualTo(1L);

        DocumentOperation operation = documentOperationRepository.saveAndFlush(DocumentOperation.builder()
                .document(document)
                .actor(owner)
                .operationId(UUID.randomUUID())
                .clientSessionId("mapping-session")
                .baseVersion(0L)
                .serverVersion(1L)
                .operationType(DocumentOperationType.INSERT_TEXT)
                .payload("{\"text\":\"after\"}")
                .build());

        entityManager.clear();

        Document reloadedDocument = documentRepository.findById(document.getId()).orElseThrow();
        DocumentOperation reloadedOperation = documentOperationRepository.findById(operation.getId()).orElseThrow();

        assertThat(reloadedDocument.getCurrentVersion()).isEqualTo(1L);
        assertThat(reloadedDocument.getVersion()).isEqualTo(2L);
        assertThat(reloadedOperation.getServerVersion()).isEqualTo(1L);
        assertThat(reloadedOperation.getBaseVersion()).isEqualTo(0L);
        assertThat(reloadedOperation.getDocument().getId()).isEqualTo(document.getId());
        assertThat(reloadedOperation.getActor().getId()).isEqualTo(owner.getId());
    }
}
