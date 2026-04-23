package com.mwang.backend.repositories;

import com.mwang.backend.domain.*;
import com.mwang.backend.testcontainers.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DocumentOperationOutboxRepositoryTest extends AbstractIntegrationTest {

    @Autowired DocumentOperationRepository operationRepo;
    @Autowired UserRepository userRepo;
    @Autowired DocumentRepository documentRepo;

    private DocumentOperation savedOp;

    @BeforeEach
    void setUp() {
        User user = userRepo.save(User.builder()
                .username("outbox-repo-" + UUID.randomUUID())
                .email(UUID.randomUUID() + "@test.com")
                .build());
        Document doc = documentRepo.save(Document.builder()
                .title("Outbox Test Doc")
                .content("{\"blocks\":[]}")
                .owner(user)
                .build());
        savedOp = operationRepo.save(DocumentOperation.builder()
                .document(doc)
                .actor(user)
                .operationId(UUID.randomUUID())
                .clientSessionId("sess")
                .baseVersion(0L)
                .serverVersion(1L)
                .operationType(DocumentOperationType.INSERT_TEXT)
                .payload("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}")
                .build());
    }

    @Test
    @Transactional
    void claimBatch_returnsEligibleRow() {
        List<DocumentOperation> claimed = operationRepo.claimBatch(Instant.now(), 10);
        assertThat(claimed).extracting(DocumentOperation::getId).contains(savedOp.getId());
    }

    @Test
    void claimBatch_excludesPoisonedRow() {
        operationRepo.markPoison(savedOp.getId(), Instant.now());
        List<DocumentOperation> claimed = operationRepo.claimBatch(Instant.now(), 10);
        assertThat(claimed).extracting(DocumentOperation::getId).doesNotContain(savedOp.getId());
    }

    @Test
    void claimBatch_excludesRowWithFutureNextAttemptAt() {
        operationRepo.recordFailure(savedOp.getId(), 1, "err", Instant.now().plusSeconds(60));
        List<DocumentOperation> claimed = operationRepo.claimBatch(Instant.now(), 10);
        assertThat(claimed).extracting(DocumentOperation::getId).doesNotContain(savedOp.getId());
    }

    @Test
    void markPublished_setsTimestampAndClearsError() {
        operationRepo.recordFailure(savedOp.getId(), 1, "transient", Instant.now().minusSeconds(1));
        operationRepo.markPublished(savedOp.getId(), Instant.now());
        DocumentOperation reloaded = operationRepo.findById(savedOp.getId()).orElseThrow();
        assertThat(reloaded.getPublishedToKafkaAt()).isNotNull();
        assertThat(reloaded.getKafkaLastError()).isNull();
    }

    @Test
    void recordFailure_incrementsAttemptsAndSetsNextAttemptAt() {
        Instant nextAt = Instant.now().plusSeconds(5);
        operationRepo.recordFailure(savedOp.getId(), 1, "broker down", nextAt);
        DocumentOperation reloaded = operationRepo.findById(savedOp.getId()).orElseThrow();
        assertThat(reloaded.getKafkaPublishAttempts()).isEqualTo(1);
        assertThat(reloaded.getKafkaLastError()).isEqualTo("broker down");
        assertThat(reloaded.getNextAttemptAt()).isNotNull();
    }

    @Test
    void markPoison_setsPoisonAt() {
        operationRepo.markPoison(savedOp.getId(), Instant.now());
        DocumentOperation reloaded = operationRepo.findById(savedOp.getId()).orElseThrow();
        assertThat(reloaded.getKafkaPoisonAt()).isNotNull();
    }

    @Test
    void countPending_reflectsUnpublishedRows() {
        long before = operationRepo.countPending();
        operationRepo.markPublished(savedOp.getId(), Instant.now());
        long after = operationRepo.countPending();
        assertThat(after).isLessThan(before);
    }

    @Test
    void countPoison_reflectsPoisonRows() {
        long before = operationRepo.countPoison();
        operationRepo.markPoison(savedOp.getId(), Instant.now());
        long after = operationRepo.countPoison();
        assertThat(after).isGreaterThan(before);
    }
}
