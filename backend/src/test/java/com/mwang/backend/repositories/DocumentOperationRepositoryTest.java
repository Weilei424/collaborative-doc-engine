package com.mwang.backend.repositories;

import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentOperation;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.domain.User;
import com.mwang.backend.testcontainers.AbstractRepositoryTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentOperationRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentOperationRepository documentOperationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextStartsWithV2MigrationApplied() {
        Integer currentVersionColumns = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_name = 'documents'
                  AND column_name = 'current_version'
                """,
                Integer.class
        );

        Integer operationTableCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_name = 'document_operations'
                """,
                Integer.class
        );

        assertThat(currentVersionColumns).isEqualTo(1);
        assertThat(operationTableCount).isEqualTo(1);
    }

    @Test
    void documentStartsWithCurrentVersionZero() {
        User owner = userRepository.saveAndFlush(newUser("owner-zero"));

        Document saved = documentRepository.saveAndFlush(Document.builder()
                .title("Versioned document")
                .content("hello")
                .owner(owner)
                .visibility(DocumentVisibility.PRIVATE)
                .build());

        assertThat(saved.getCurrentVersion()).isZero();
    }

    @Test
    void canPersistAndReadOperationsOrderedByServerVersion() {
        User owner = userRepository.saveAndFlush(newUser("owner-ordered"));
        Document document = documentRepository.saveAndFlush(newDocument(owner, "Ordered doc"));

        documentOperationRepository.saveAndFlush(newOperation(document, owner, 1L, 0L));
        documentOperationRepository.saveAndFlush(newOperation(document, owner, 2L, 1L));
        documentOperationRepository.saveAndFlush(newOperation(document, owner, 3L, 2L));

        List<DocumentOperation> operations = documentOperationRepository
                .findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(document.getId(), 0L);

        assertThat(operations)
                .extracting(DocumentOperation::getServerVersion)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void cannotInsertDuplicateOperationIdForSameDocument() {
        User owner = userRepository.saveAndFlush(newUser("owner-dup-op"));
        Document document = documentRepository.saveAndFlush(newDocument(owner, "Duplicate op document"));
        UUID operationId = UUID.randomUUID();

        documentOperationRepository.saveAndFlush(newOperation(document, owner, operationId, 1L, 0L));

        assertThatThrownBy(() -> documentOperationRepository.saveAndFlush(newOperation(document, owner, operationId, 2L, 1L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cannotInsertDuplicateServerVersionForSameDocument() {
        User owner = userRepository.saveAndFlush(newUser("owner-dup-server"));
        Document document = documentRepository.saveAndFlush(newDocument(owner, "Duplicate version document"));

        documentOperationRepository.saveAndFlush(newOperation(document, owner, 1L, 0L));

        assertThatThrownBy(() -> documentOperationRepository.saveAndFlush(newOperation(document, owner, 1L, 0L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void canLookupLatestOperationForDocument() {
        User owner = userRepository.saveAndFlush(newUser("owner-latest"));
        Document document = documentRepository.saveAndFlush(newDocument(owner, "Latest document"));

        documentOperationRepository.saveAndFlush(newOperation(document, owner, 1L, 0L));
        documentOperationRepository.saveAndFlush(newOperation(document, owner, 2L, 1L));

        assertThat(documentOperationRepository.findTopByDocumentIdOrderByServerVersionDesc(document.getId()))
                .isPresent()
                .get()
                .extracting(DocumentOperation::getServerVersion)
                .isEqualTo(2L);
    }

    @Test
    void canFetchOperationsAfterBaseVersion() {
        User owner = userRepository.saveAndFlush(newUser("owner-after-base"));
        Document document = documentRepository.saveAndFlush(newDocument(owner, "After base document"));

        documentOperationRepository.saveAndFlush(newOperation(document, owner, 1L, 0L));
        documentOperationRepository.saveAndFlush(newOperation(document, owner, 2L, 1L));
        documentOperationRepository.saveAndFlush(newOperation(document, owner, 3L, 2L));

        List<DocumentOperation> operations = documentOperationRepository
                .findByDocumentIdAndServerVersionBetweenOrderByServerVersionAsc(document.getId(), 2L, 3L);

        assertThat(operations)
                .extracting(DocumentOperation::getServerVersion)
                .containsExactly(2L, 3L);
    }

    private User newUser(String seed) {
        return User.builder()
                .username(seed)
                .email(seed + "@example.com")
                .passwordHash("hashed-password")
                .build();
    }

    private Document newDocument(User owner, String title) {
        return Document.builder()
                .title(title)
                .content("initial content")
                .owner(owner)
                .visibility(DocumentVisibility.PRIVATE)
                .build();
    }

    private DocumentOperation newOperation(Document document, User actor, long serverVersion, long baseVersion) {
        return newOperation(document, actor, UUID.randomUUID(), serverVersion, baseVersion);
    }

    private DocumentOperation newOperation(Document document, User actor, UUID operationId, long serverVersion, long baseVersion) {
        return DocumentOperation.builder()
                .document(document)
                .actor(actor)
                .operationId(operationId)
                .clientSessionId("session-" + serverVersion)
                .baseVersion(baseVersion)
                .serverVersion(serverVersion)
                .operationType(DocumentOperationType.INSERT_TEXT)
                .payload("{\"text\":\"hello\"}")
                .build();
    }
}
