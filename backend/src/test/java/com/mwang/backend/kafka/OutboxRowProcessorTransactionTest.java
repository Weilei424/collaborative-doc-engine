package com.mwang.backend.kafka;

import com.mwang.backend.domain.*;
import com.mwang.backend.repositories.DocumentOperationRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.repositories.UserRepository;
import com.mwang.backend.testcontainers.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestPropertySource(properties = "collaboration.outbox.poll-interval-ms=100000")
@SuppressWarnings("unchecked")
class OutboxRowProcessorTransactionTest extends AbstractIntegrationTest {

    @MockitoBean
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired OutboxRowProcessor processor;
    @Autowired DocumentOperationRepository operationRepo;
    @Autowired UserRepository userRepo;
    @Autowired DocumentRepository documentRepo;

    @AfterEach
    void tearDown() {
        operationRepo.deleteAll();
        documentRepo.deleteAll();
        userRepo.deleteAll();
    }

    @Test
    void outboxRowProcessor_isWrappedInSpringProxy() {
        // @Transactional only takes effect when the bean is invoked through Spring's AOP proxy.
        // If someone removes @Transactional, Spring may stop proxying the bean and this check fails.
        assertThat(AopUtils.isAopProxy(processor)).isTrue();
    }

    @Test
    void claimAndProcess_successCommitsPublishedAtBeforeReturning() {
        DocumentOperation op = savedOp(0);

        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        boolean processed = processor.claimAndProcess(Instant.now());

        assertThat(processed).isTrue();
        // This test method is NOT @Transactional, so any read here is a fresh query.
        // publishedToKafkaAt must be visible now — proving the per-row transaction committed
        // before claimAndProcess returned, not deferred to some outer batch transaction.
        assertThat(operationRepo.findById(op.getId()).orElseThrow().getPublishedToKafkaAt())
                .as("publishedToKafkaAt must be committed by the time claimAndProcess returns")
                .isNotNull();
    }

    @Test
    void claimAndProcess_kafkaFailureCommitsRecordFailureRatherThanRollingBack() {
        DocumentOperation op = savedOp(0);

        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(failed);

        processor.claimAndProcess(Instant.now());

        // The Kafka failure is caught inside claimAndProcess, so the transaction commits
        // with the recordFailure state rather than rolling back. This is the key property
        // that makes the outbox retry correctly: failure state is durable.
        DocumentOperation reloaded = operationRepo.findById(op.getId()).orElseThrow();
        assertThat(reloaded.getKafkaPublishAttempts())
                .as("attempt count must be committed after Kafka failure")
                .isEqualTo(1);
        assertThat(reloaded.getNextAttemptAt())
                .as("nextAttemptAt must be committed after Kafka failure")
                .isNotNull();
        assertThat(reloaded.getPublishedToKafkaAt())
                .as("publishedToKafkaAt must remain null after Kafka failure")
                .isNull();
    }

    private DocumentOperation savedOp(int attempts) {
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        User user = userRepo.save(User.builder()
                .username("tx-test-" + uid)
                .email(uid + "@test.com")
                .build());
        Document doc = documentRepo.save(Document.builder()
                .title("TX Test Doc")
                .content("{\"blocks\":[]}")
                .owner(user)
                .build());
        return operationRepo.save(DocumentOperation.builder()
                .document(doc)
                .actor(user)
                .operationId(UUID.randomUUID())
                .clientSessionId("tx-test-sess")
                .baseVersion(0L)
                .serverVersion(1L)
                .operationType(DocumentOperationType.INSERT_TEXT)
                .payload("{\"path\":[0],\"offset\":0,\"text\":\"x\"}")
                .kafkaPublishAttempts(attempts)
                .build());
    }
}
