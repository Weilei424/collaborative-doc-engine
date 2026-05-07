package com.mwang.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentOperation;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.domain.User;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.repositories.DocumentOperationRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.repositories.UserRepository;
import com.mwang.backend.testcontainers.AbstractIntegrationTest;
import com.mwang.backend.web.model.SubmitOperationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
class DocumentOperationConcurrencyTest extends AbstractIntegrationTest {

    @MockitoBean private CurrentUserProvider currentUserProvider;
    @Autowired private DocumentOperationService operationService;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentOperationRepository operationRepository;
    @Autowired private UserRepository userRepository;

    private User actor;
    private Document document;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        actor = userRepository.save(User.builder()
                .username("conc-user-" + uid).email("conc-" + uid + "@test.com")
                .passwordHash("hash").build());
        document = documentRepository.save(Document.builder()
                .title("Concurrency Test Doc")
                .content("{\"children\":[{\"type\":\"paragraph\",\"text\":\"\",\"children\":[]}]}")
                .owner(actor).visibility(DocumentVisibility.PRIVATE).currentVersion(0L).build());
        when(currentUserProvider.requireCurrentUser(any(SimpMessageHeaderAccessor.class))).thenReturn(actor);
    }

    @AfterEach
    void tearDown() {
        operationRepository.deleteAll();
        documentRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void concurrentSubmits_serializeWithCas() throws Exception {
        JsonNode payload = mapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);
        when(accessor.getSessionId()).thenReturn("test-session");
        when(accessor.getUser()).thenReturn(() -> "conc-user");

        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        executor.submit(() -> { startLatch.await(); operationService.submitOperation(document.getId(),
                new SubmitOperationRequest(UUID.randomUUID(), 0L, DocumentOperationType.INSERT_TEXT, payload), accessor); return null; });
        executor.submit(() -> { startLatch.await(); operationService.submitOperation(document.getId(),
                new SubmitOperationRequest(UUID.randomUUID(), 0L, DocumentOperationType.INSERT_TEXT, payload), accessor); return null; });

        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        await().atMost(10, TimeUnit.SECONDS).until(() ->
                operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                        document.getId(), 0L).size() >= 2);

        List<Long> versions = operationRepository
                .findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(document.getId(), 0L)
                .stream().map(DocumentOperation::getServerVersion).sorted().toList();
        assertThat(versions).containsExactly(1L, 2L);

        Document updated = documentRepository.findById(document.getId()).orElseThrow();
        assertThat(updated.getCurrentVersion()).isEqualTo(2L);
    }

    @Test
    void concurrentSubmits_withDuplicateOperationId_onlyOneRowInDb() throws Exception {
        JsonNode payload = mapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);
        when(accessor.getSessionId()).thenReturn("test-session");
        when(accessor.getUser()).thenReturn(() -> "conc-user");

        UUID sharedOpId = UUID.randomUUID();
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        executor.submit(() -> { startLatch.await(); operationService.submitOperation(document.getId(),
                new SubmitOperationRequest(sharedOpId, 0L, DocumentOperationType.INSERT_TEXT, payload), accessor); return null; });
        executor.submit(() -> { startLatch.await(); operationService.submitOperation(document.getId(),
                new SubmitOperationRequest(sharedOpId, 0L, DocumentOperationType.INSERT_TEXT, payload), accessor); return null; });

        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        await().atMost(10, TimeUnit.SECONDS).until(() ->
                operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                        document.getId(), 0L).size() >= 1);

        // Allow a moment for all paths (idempotent fast path or batcher drain) to settle
        Thread.sleep(100);

        assertThat(operationRepository.findAll()).hasSize(1);
        Document updated = documentRepository.findById(document.getId()).orElseThrow();
        assertThat(updated.getCurrentVersion()).isEqualTo(1L);
    }

    @Test
    void nConcurrentSubmitters_allOperationsAccepted_versionsAreContiguous() throws Exception {
        int n = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch enqueuedLatch = new CountDownLatch(n);
        SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);
        when(accessor.getSessionId()).thenReturn("concurrent-session");
        when(accessor.getUser()).thenReturn(() -> "conc-user");

        for (int i = 0; i < n; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    JsonNode payload = mapper.readTree(
                            "{\"path\":[0],\"offset\":0,\"text\":\"x\"}");
                    operationService.submitOperation(document.getId(),
                            new SubmitOperationRequest(UUID.randomUUID(), 0L,
                                    DocumentOperationType.INSERT_TEXT, payload), accessor);
                } catch (Exception e) {
                    // ignore for count
                } finally {
                    enqueuedLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertThat(enqueuedLatch.await(30, TimeUnit.SECONDS)).isTrue();

        // Wait for batcher to drain all n ops
        await().atMost(30, TimeUnit.SECONDS).until(() ->
                operationRepository.findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(
                        document.getId(), 0L).size() >= n);

        List<Long> versions = operationRepository
                .findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(document.getId(), 0L)
                .stream().map(DocumentOperation::getServerVersion).sorted().toList();
        assertThat(versions).hasSize(n);
        assertThat(versions).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    }
}
