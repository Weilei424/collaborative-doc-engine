package com.mwang.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentOperation;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentOperationRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.repositories.UserRepository;
import com.mwang.backend.testcontainers.AbstractIntegrationTest;
import com.mwang.backend.web.model.SubmitOperationRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
class DocumentOperationBatchingIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean private CurrentUserProvider currentUserProvider;

    @Autowired private DocumentOperationService operationService;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentOperationRepository operationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MeterRegistry meterRegistry;

    private User actor;
    private Document document;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        actor = userRepository.save(User.builder()
                .username("batch-user-" + uid)
                .email("batch-" + uid + "@test.com")
                .passwordHash("hash").build());
        document = documentRepository.save(Document.builder()
                .title("Batching Test Doc")
                .content("{\"children\":[{\"type\":\"paragraph\",\"text\":\"\",\"children\":[]}]}")
                .owner(actor)
                .visibility(DocumentVisibility.PRIVATE)
                .currentVersion(0L).build());
        when(currentUserProvider.requireCurrentUser(any(SimpMessageHeaderAccessor.class))).thenReturn(actor);
    }

    @AfterEach
    void tearDown() {
        operationRepository.deleteAll();
        documentRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void fiveSubmitters_allOpsAccepted_retriesNearZero() throws Exception {
        int submitters = 5;
        int opsPerSubmitter = 10;
        int totalOps = submitters * opsPerSubmitter;

        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(submitters);
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

        SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);
        when(accessor.getSessionId()).thenReturn("batch-session");
        when(accessor.getUser()).thenReturn(() -> "batch-user");

        for (int s = 0; s < submitters; s++) {
            final int submitterIdx = s;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < opsPerSubmitter; i++) {
                        try {
                            JsonNode payload = mapper.readTree(
                                    "{\"path\":[0],\"offset\":0,\"text\":\"x\"}");
                            operationService.submitOperation(
                                    document.getId(),
                                    new SubmitOperationRequest(UUID.randomUUID(), 0L,
                                            DocumentOperationType.INSERT_TEXT, payload),
                                    accessor);
                        } catch (Exception e) {
                            errors.add(e);
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        startLatch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();

        await().atMost(30, TimeUnit.SECONDS)
               .until(() -> operationRepository
                       .findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                               document.getId(), 0L)
                       .size() >= totalOps);

        List<DocumentOperation> dbOps = operationRepository
                .findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(document.getId(), 0L);
        assertThat(dbOps).hasSize(totalOps);

        long distinctVersions = dbOps.stream()
                .map(DocumentOperation::getServerVersion).distinct().count();
        assertThat(distinctVersions).isEqualTo(totalOps);

        // operations.retries{attempt>2} is below 10% of total ops
        double retriesAttempt3 = meterRegistry.counter("operations.retries", "attempt", "3").count();
        double retriesAttempt4 = meterRegistry.counter("operations.retries", "attempt", "4").count();
        double retriesAttempt5 = meterRegistry.counter("operations.retries", "attempt", "5").count();
        double highRetries = retriesAttempt3 + retriesAttempt4 + retriesAttempt5;
        assertThat(highRetries).isLessThan(totalOps * 0.10);

        // operations.batch.size was recorded (batching actually happened)
        assertThat(meterRegistry.find("operations.batch.size").summary()).isNotNull();
        assertThat(meterRegistry.find("operations.batch.size").summary().count()).isGreaterThan(0);
    }
}
