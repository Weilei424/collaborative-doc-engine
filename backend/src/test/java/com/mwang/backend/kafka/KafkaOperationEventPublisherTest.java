package com.mwang.backend.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class KafkaOperationEventPublisherTest {

    private KafkaTemplate<String, String> kafkaTemplate;
    private ObjectMapper objectMapper;
    private KafkaOperationEventPublisher publisher;

    private UUID documentId;
    private UUID operationId;
    private UUID actorId;
    private AcceptedOperationDomainEvent event;

    @BeforeEach
    void setUp() throws Exception {
        kafkaTemplate = mock(KafkaTemplate.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        // maxAttempts=1 so tests don't sleep through backoff
        RetryTemplate retryTemplate = RetryTemplate.builder().maxAttempts(1).build();

        publisher = new KafkaOperationEventPublisher(
                kafkaTemplate, objectMapper, retryTemplate,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        ReflectionTestUtils.setField(publisher, "operationsTopic", "document-operations");

        // Default: send() returns a completed future so .get() doesn't NPE
        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        documentId = UUID.randomUUID();
        operationId = UUID.randomUUID();
        actorId = UUID.randomUUID();

        JsonNode payload = objectMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        AcceptedOperationResponse response = new AcceptedOperationResponse(
                operationId, documentId, 1L,
                DocumentOperationType.INSERT_TEXT, payload,
                actorId, "sess-1", Instant.now());
        event = new AcceptedOperationDomainEvent(response, 0L);
    }

    @Test
    void publishesToCorrectTopicWithDocumentIdAsKey() {
        publisher.onAcceptedOperation(event);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("document-operations"), keyCaptor.capture(), any(String.class));
        assertThat(keyCaptor.getValue()).isEqualTo(documentId.toString());
    }

    @Test
    void payloadContainsCorrectOperationFields() throws Exception {
        publisher.onAcceptedOperation(event);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("document-operations"), any(), valueCaptor.capture());

        KafkaAcceptedOperationEvent published =
                objectMapper.readValue(valueCaptor.getValue(), KafkaAcceptedOperationEvent.class);
        assertThat(published.operationId()).isEqualTo(operationId);
        assertThat(published.documentId()).isEqualTo(documentId);
        assertThat(published.actorUserId()).isEqualTo(actorId);
        assertThat(published.baseVersion()).isEqualTo(0L);
        assertThat(published.serverVersion()).isEqualTo(1L);
        assertThat(published.operationType()).isEqualTo(DocumentOperationType.INSERT_TEXT);
    }

    @Test
    void recoversGracefullyWhenAllRetriesExhausted() {
        // Simulate async delivery failure — the representative broker failure path
        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(failedFuture);

        KafkaOperationEventPublisher failingPublisher =
                new KafkaOperationEventPublisher(kafkaTemplate, objectMapper,
                        RetryTemplate.builder().maxAttempts(1).build(),
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        ReflectionTestUtils.setField(failingPublisher, "operationsTopic", "document-operations");

        assertThatNoException().isThrownBy(() -> failingPublisher.onAcceptedOperation(event));
        verify(kafkaTemplate, times(1)).send(any(), any(), any());
    }

    @Test
    void onAcceptedOperation_recordsPublishKafkaTimer() throws Exception {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        KafkaOperationEventPublisher pub =
                new KafkaOperationEventPublisher(kafkaTemplate, objectMapper,
                        RetryTemplate.builder().maxAttempts(1).build(), registry);
        ReflectionTestUtils.setField(
                pub, "operationsTopic", "document-operations");

        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        pub.onAcceptedOperation(event);

        assertThat(registry.find("publishKafka").timer().count()).isEqualTo(1);
    }
}
