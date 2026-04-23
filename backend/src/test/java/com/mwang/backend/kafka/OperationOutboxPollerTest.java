package com.mwang.backend.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentOperation;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentOperationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class OperationOutboxPollerTest {

    private DocumentOperationRepository repo;
    private KafkaTemplate<String, String> kafkaTemplate;
    private OperationOutboxPoller poller;
    private ObjectMapper objectMapper;

    private UUID documentId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        repo = mock(DocumentOperationRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();

        poller = new OperationOutboxPoller(repo, kafkaTemplate, objectMapper, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(poller, "operationsTopic", "document-operations");
        ReflectionTestUtils.setField(poller, "batchSize", 100);
        ReflectionTestUtils.setField(poller, "maxAttempts", 10);
        ReflectionTestUtils.setField(poller, "backoffMs", 1000L);
        ReflectionTestUtils.setField(poller, "backoffCapMs", 60000L);

        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        documentId = UUID.randomUUID();
        actorId = UUID.randomUUID();
    }

    private DocumentOperation buildOp(int attempts) {
        Document doc = mock(Document.class);
        when(doc.getId()).thenReturn(documentId);
        User actor = mock(User.class);
        when(actor.getId()).thenReturn(actorId);

        DocumentOperation op = DocumentOperation.builder()
                .id(UUID.randomUUID())
                .document(doc)
                .actor(actor)
                .operationId(UUID.randomUUID())
                .clientSessionId("sess")
                .baseVersion(0L)
                .serverVersion(1L)
                .operationType(DocumentOperationType.INSERT_TEXT)
                .payload("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}")
                .kafkaPublishAttempts(attempts)
                .build();
        return op;
    }

    @Test
    void poll_doesNothingWhenBatchIsEmpty() {
        when(repo.claimBatch(any(), anyInt())).thenReturn(List.of());
        poller.poll();
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void poll_callsMarkPublishedOnSuccess() {
        DocumentOperation op = buildOp(0);
        when(repo.claimBatch(any(), anyInt())).thenReturn(List.of(op));

        poller.poll();

        verify(repo).markPublished(eq(op.getId()), any(Instant.class));
        verify(repo, never()).recordFailure(any(), anyInt(), any(), any());
        verify(repo, never()).markPoison(any(), any(), anyInt(), any());
    }

    @Test
    void poll_sendsWithDocumentIdAsKey() {
        DocumentOperation op = buildOp(0);
        when(repo.claimBatch(any(), anyInt())).thenReturn(List.of(op));

        poller.poll();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("document-operations"), keyCaptor.capture(), any());
        assertThat(keyCaptor.getValue()).isEqualTo(documentId.toString());
    }

    @Test
    void poll_callsRecordFailureOnTransientError() {
        DocumentOperation op = buildOp(0);
        when(repo.claimBatch(any(), anyInt())).thenReturn(List.of(op));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(failed);

        poller.poll();

        verify(repo).recordFailure(eq(op.getId()), eq(1), contains("broker down"), any(Instant.class));
        verify(repo, never()).markPublished(any(), any());
        verify(repo, never()).markPoison(any(), any(), anyInt(), any());
    }

    @Test
    void poll_callsMarkPoisonWhenMaxAttemptsReached() {
        DocumentOperation op = buildOp(9); // next attempt = 10 = maxAttempts
        when(repo.claimBatch(any(), anyInt())).thenReturn(List.of(op));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("still down"));
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(failed);

        poller.poll();

        verify(repo).markPoison(eq(op.getId()), any(Instant.class), eq(10), contains("still down"));
        verify(repo, never()).markPublished(any(), any());
        verify(repo, never()).recordFailure(any(), anyInt(), any(), any());
    }

    @Test
    void poll_oneFailureDoesNotPreventOtherRowsFromBeingPublished() {
        DocumentOperation good = buildOp(0);
        DocumentOperation bad = buildOp(0);
        when(repo.claimBatch(any(), anyInt())).thenReturn(List.of(bad, good));

        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("fail"));
        CompletableFuture<SendResult<String, String>> ok = CompletableFuture.completedFuture(mock(SendResult.class));
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(failed, ok);

        poller.poll();

        verify(repo).markPublished(eq(good.getId()), any());
        verify(repo).recordFailure(eq(bad.getId()), anyInt(), any(), any());
    }

    @Test
    void backoff_doublesEachAttemptAndCapsAtMax() {
        assertThat(poller.backoff(1)).isEqualTo(1000L);
        assertThat(poller.backoff(2)).isEqualTo(2000L);
        assertThat(poller.backoff(3)).isEqualTo(4000L);
        assertThat(poller.backoff(7)).isEqualTo(60000L); // cap
        assertThat(poller.backoff(10)).isEqualTo(60000L);
    }

    @Test
    void poll_recordsPublishKafkaTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OperationOutboxPoller timedPoller = new OperationOutboxPoller(repo, kafkaTemplate, objectMapper, registry);
        ReflectionTestUtils.setField(timedPoller, "operationsTopic", "document-operations");
        ReflectionTestUtils.setField(timedPoller, "batchSize", 100);
        ReflectionTestUtils.setField(timedPoller, "maxAttempts", 10);
        ReflectionTestUtils.setField(timedPoller, "backoffMs", 1000L);
        ReflectionTestUtils.setField(timedPoller, "backoffCapMs", 60000L);

        DocumentOperation op = buildOp(0);
        when(repo.claimBatch(any(), anyInt())).thenReturn(List.of(op));

        timedPoller.poll();

        assertThat(registry.find("publishKafka").timer().count()).isEqualTo(1);
    }
}
