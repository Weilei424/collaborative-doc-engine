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
class OutboxRowProcessorTest {

    private DocumentOperationRepository repo;
    private KafkaTemplate<String, String> kafkaTemplate;
    private OutboxRowProcessor processor;
    private ObjectMapper objectMapper;

    private UUID documentId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        repo = mock(DocumentOperationRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();

        processor = new OutboxRowProcessor(repo, kafkaTemplate, objectMapper, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(processor, "operationsTopic", "document-operations");
        ReflectionTestUtils.setField(processor, "maxAttempts", 10);
        ReflectionTestUtils.setField(processor, "backoffMs", 1000L);
        ReflectionTestUtils.setField(processor, "backoffCapMs", 60000L);

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

        return DocumentOperation.builder()
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
    }

    @Test
    void claimAndProcess_returnsFalseWhenOutboxIsEmpty() {
        when(repo.claimBatch(any(), eq(1))).thenReturn(List.of());
        assertThat(processor.claimAndProcess(Instant.now())).isFalse();
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void claimAndProcess_returnsTrueAndCallsMarkPublishedOnSuccess() {
        DocumentOperation op = buildOp(0);
        when(repo.claimBatch(any(), eq(1))).thenReturn(List.of(op));

        assertThat(processor.claimAndProcess(Instant.now())).isTrue();

        verify(repo).markPublished(eq(op.getId()), any(Instant.class));
        verify(repo, never()).recordFailure(any(), anyInt(), any(), any());
        verify(repo, never()).markPoison(any(), any(), anyInt(), any());
    }

    @Test
    void claimAndProcess_sendsWithDocumentIdAsKey() {
        DocumentOperation op = buildOp(0);
        when(repo.claimBatch(any(), eq(1))).thenReturn(List.of(op));

        processor.claimAndProcess(Instant.now());

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("document-operations"), keyCaptor.capture(), any());
        assertThat(keyCaptor.getValue()).isEqualTo(documentId.toString());
    }

    @Test
    void claimAndProcess_callsRecordFailureOnTransientError() {
        DocumentOperation op = buildOp(0);
        when(repo.claimBatch(any(), eq(1))).thenReturn(List.of(op));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(failed);

        processor.claimAndProcess(Instant.now());

        verify(repo).recordFailure(eq(op.getId()), eq(1), contains("broker down"), any(Instant.class));
        verify(repo, never()).markPublished(any(), any());
        verify(repo, never()).markPoison(any(), any(), anyInt(), any());
    }

    @Test
    void claimAndProcess_callsMarkPoisonWhenMaxAttemptsReached() {
        DocumentOperation op = buildOp(9); // next attempt = 10 = maxAttempts
        when(repo.claimBatch(any(), eq(1))).thenReturn(List.of(op));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("still down"));
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(failed);

        processor.claimAndProcess(Instant.now());

        verify(repo).markPoison(eq(op.getId()), any(Instant.class), eq(10), contains("still down"));
        verify(repo, never()).markPublished(any(), any());
        verify(repo, never()).recordFailure(any(), anyInt(), any(), any());
    }

    @Test
    void claimAndProcess_recordsPublishKafkaTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxRowProcessor timedProcessor =
                new OutboxRowProcessor(repo, kafkaTemplate, objectMapper, registry);
        ReflectionTestUtils.setField(timedProcessor, "operationsTopic", "document-operations");
        ReflectionTestUtils.setField(timedProcessor, "maxAttempts", 10);
        ReflectionTestUtils.setField(timedProcessor, "backoffMs", 1000L);
        ReflectionTestUtils.setField(timedProcessor, "backoffCapMs", 60000L);

        DocumentOperation op = buildOp(0);
        when(repo.claimBatch(any(), eq(1))).thenReturn(List.of(op));

        timedProcessor.claimAndProcess(Instant.now());

        assertThat(registry.find("publishKafka").timer().count()).isEqualTo(1);
    }

    @Test
    void backoff_doublesEachAttemptAndCapsAtMax() {
        assertThat(processor.backoff(1)).isEqualTo(1000L);
        assertThat(processor.backoff(2)).isEqualTo(2000L);
        assertThat(processor.backoff(3)).isEqualTo(4000L);
        assertThat(processor.backoff(7)).isEqualTo(60000L); // cap
        assertThat(processor.backoff(10)).isEqualTo(60000L);
    }
}
