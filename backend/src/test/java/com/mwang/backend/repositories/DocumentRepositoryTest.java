package com.mwang.backend.repositories;

import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.domain.User;
import com.mwang.backend.testcontainers.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class DocumentRepositoryTest extends AbstractIntegrationTest {

    @Autowired private DocumentRepository documentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private User owner;
    private Document document;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.builder()
                .username("repo-test-user-" + System.nanoTime())
                .email("repo-" + System.nanoTime() + "@test.com")
                .passwordHash("hash").build());

        document = documentRepository.save(Document.builder()
                .title("CAS Test Doc")
                .content("{\"children\":[]}")
                .owner(owner)
                .visibility(DocumentVisibility.PRIVATE)
                .currentVersion(0L)
                .build());
    }

    @AfterEach
    void tearDown() {
        documentRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @Transactional
    void tryAdvanceVersion_correctExpected_returnsOneAndUpdatesDocument() {
        int rows = documentRepository.tryAdvanceVersion(
                document.getId(), 0L, 1L, "{\"children\":[{\"type\":\"paragraph\"}]}");

        assertThat(rows).isEqualTo(1);
        Document updated = documentRepository.findById(document.getId()).orElseThrow();
        assertThat(updated.getCurrentVersion()).isEqualTo(1L);
        assertThat(updated.getContent()).contains("paragraph");
    }

    @Test
    void tryAdvanceVersion_advancesJpaVersion_staleSave_throwsOptimisticLockException() {
        // TX 1: capture entity snapshot before the CAS commit (simulates a concurrent REST load)
        Document staleSnapshot = documentRepository.findById(document.getId()).orElseThrow();

        // TX 2: CAS commit in its own transaction — mirrors production's REQUIRES_NEW committer.
        // tryAdvanceVersion is @Modifying and requires an active transaction.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        int rows = tx.execute(status ->
                documentRepository.tryAdvanceVersion(
                        document.getId(), 0L, 1L, "{\"children\":[{\"type\":\"paragraph\"}]}"));
        assertThat(rows).isEqualTo(1);

        // TX 3: stale REST save must fail — the @Version mismatch proves the JPA optimistic
        // lock is not bypassed by the collaboration CAS update
        staleSnapshot.setTitle("Stale REST update");
        assertThatThrownBy(() -> documentRepository.saveAndFlush(staleSnapshot))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @Transactional
    void tryAdvanceVersion_staleExpected_returnsZeroAndLeavesDocumentUnchanged() {
        // Advance manually first so currentVersion is 1
        documentRepository.tryAdvanceVersion(document.getId(), 0L, 1L, "{\"children\":[]}");

        // Now try with stale expected=0 again
        int rows = documentRepository.tryAdvanceVersion(
                document.getId(), 0L, 1L, "{\"children\":[{\"type\":\"new\"}]}");

        assertThat(rows).isEqualTo(0);
        Document unchanged = documentRepository.findById(document.getId()).orElseThrow();
        assertThat(unchanged.getCurrentVersion()).isEqualTo(1L);
        assertThat(unchanged.getContent()).doesNotContain("new");
    }
}
