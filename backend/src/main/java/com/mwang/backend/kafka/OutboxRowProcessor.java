package com.mwang.backend.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.domain.DocumentOperation;
import com.mwang.backend.repositories.DocumentOperationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Component
class OutboxRowProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxRowProcessor.class);

    private final DocumentOperationRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Timer publishKafkaTimer;

    @Value("${kafka.topics.document-operations}")
    private String operationsTopic;

    @Value("${collaboration.outbox.max-attempts:10}")
    private int maxAttempts;

    @Value("${collaboration.outbox.backoff-ms:1000}")
    private long backoffMs;

    @Value("${collaboration.outbox.backoff-cap-ms:60000}")
    private long backoffCapMs;

    OutboxRowProcessor(
            DocumentOperationRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.publishKafkaTimer = Timer.builder("publishKafka").register(meterRegistry);
    }

    /**
     * Claims one pending outbox row, sends it to Kafka, and records the outcome in a
     * single transaction. DB and advisory locks are held only for the duration of one
     * Kafka send (bounded by delivery.timeout.ms), not for the entire batch.
     *
     * @return true if a row was claimed and processed; false if the outbox is empty.
     */
    @Transactional
    boolean claimAndProcess(Instant now) {
        List<DocumentOperation> batch = repository.claimBatch(now, 1);
        if (batch.isEmpty()) {
            return false;
        }
        processRow(batch.get(0));
        return true;
    }

    private void processRow(DocumentOperation op) {
        String payload;
        try {
            payload = serialize(op);
        } catch (JsonProcessingException e) {
            log.error("[OUTBOX] Serialization failed: id={} operationId={}", op.getId(), op.getOperationId(), e);
            recordFailureOrPoison(op, e.getMessage());
            return;
        }

        String key = op.getDocument().getId().toString();
        long start = System.nanoTime();
        try {
            kafkaTemplate.send(operationsTopic, key, payload).get();
            repository.markPublished(op.getId(), Instant.now());
            log.debug("[OUTBOX] Published: operationId={} documentId={} serverVersion={}",
                    op.getOperationId(), op.getDocument().getId(), op.getServerVersion());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailureOrPoison(op, e.getMessage());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            recordFailureOrPoison(op, cause.getMessage());
        } catch (Exception e) {
            recordFailureOrPoison(op, e.getMessage());
        } finally {
            publishKafkaTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private void recordFailureOrPoison(DocumentOperation op, String error) {
        int newAttempts = op.getKafkaPublishAttempts() + 1;
        Instant now = Instant.now();
        if (newAttempts >= maxAttempts) {
            repository.markPoison(op.getId(), now, newAttempts, error);
            log.error("[OUTBOX] Poisoned after {} attempts: operationId={} documentId={} error={}",
                    newAttempts, op.getOperationId(), op.getDocument().getId(), error);
        } else {
            Instant nextAt = now.plusMillis(backoff(newAttempts));
            repository.recordFailure(op.getId(), newAttempts, error, nextAt);
            log.warn("[OUTBOX] Attempt {}/{} failed: operationId={} nextAt={} error={}",
                    newAttempts, maxAttempts, op.getOperationId(), nextAt, error);
        }
    }

    private String serialize(DocumentOperation op) throws JsonProcessingException {
        KafkaAcceptedOperationEvent event = new KafkaAcceptedOperationEvent(
                op.getOperationId(),
                op.getDocument().getId(),
                op.getActor().getId(),
                op.getClientSessionId(),
                op.getBaseVersion(),
                op.getServerVersion(),
                op.getOperationType(),
                objectMapper.readTree(op.getPayload()),
                op.getCreatedAt()
        );
        return objectMapper.writeValueAsString(event);
    }

    long backoff(int attempts) {
        long delay = backoffMs;
        for (int i = 1; i < attempts; i++) {
            delay = Math.min(delay * 2, backoffCapMs);
        }
        return delay;
    }
}
