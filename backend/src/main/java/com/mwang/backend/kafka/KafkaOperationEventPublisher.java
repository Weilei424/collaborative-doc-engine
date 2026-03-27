package com.mwang.backend.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ExecutionException;

@Component
public class KafkaOperationEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaOperationEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final RetryTemplate retryTemplate;

    @Value("${kafka.topics.document-operations}")
    private String operationsTopic;

    public KafkaOperationEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            RetryTemplate retryTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.retryTemplate = retryTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAcceptedOperation(AcceptedOperationDomainEvent domainEvent) {
        AcceptedOperationResponse response = domainEvent.response();
        KafkaAcceptedOperationEvent kafkaEvent = new KafkaAcceptedOperationEvent(
                response.operationId(),
                response.documentId(),
                response.actorUserId(),
                response.clientSessionId(),
                domainEvent.baseVersion(),
                response.serverVersion(),
                response.operationType(),
                response.transformedPayload(),
                response.acceptedAt()
        );

        String payload;
        try {
            payload = objectMapper.writeValueAsString(kafkaEvent);
        } catch (JsonProcessingException e) {
            log.error("[KAFKA] Failed to serialize accepted operation event: documentId={} operationId={}",
                    response.documentId(), response.operationId(), e);
            return;
        }

        String key = response.documentId().toString();
        retryTemplate.execute(
                ctx -> {
                    try {
                        kafkaTemplate.send(operationsTopic, key, payload).get();
                    } catch (ExecutionException e) {
                        throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    return null;
                },
                ctx -> {
                    log.warn("[KAFKA] Failed to publish accepted operation after retries: " +
                                    "documentId={} operationId={} attempts={} error={}",
                            response.documentId(), response.operationId(),
                            ctx.getRetryCount(),
                            ctx.getLastThrowable() != null ? ctx.getLastThrowable().getMessage() : "unknown");
                    return null;
                }
        );
    }
}
