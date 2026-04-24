package com.mwang.backend.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.collaboration.RedisCollaborationEventPublisher;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class KafkaOperationNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaOperationNotificationConsumer.class);

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final RedisCollaborationEventPublisher collaborationEventPublisher;

    public KafkaOperationNotificationConsumer(ObjectMapper objectMapper,
                                               StringRedisTemplate redisTemplate,
                                               RedisCollaborationEventPublisher collaborationEventPublisher) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.collaborationEventPublisher = collaborationEventPublisher;
    }

    @KafkaListener(
            topics = "${kafka.topics.document-operations}",
            groupId = "${kafka.consumer.group-id.notification:notification-consumer-group}"
    )
    public void onAcceptedOperation(String message) throws JsonProcessingException {
        KafkaAcceptedOperationEvent event = objectMapper.readValue(message, KafkaAcceptedOperationEvent.class);

        String dedupKey = "dedup:op:" + event.operationId();
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(dedupKey, "1", Duration.ofMinutes(5));

        if (Boolean.FALSE.equals(isNew)) {
            log.debug("[NOTIFY] duplicate op {}, skipping broadcast", event.operationId());
            return;
        }

        collaborationEventPublisher.publishAcceptedOperation(
                event.documentId(),
                new AcceptedOperationResponse(
                        event.operationId(), event.documentId(), event.serverVersion(),
                        event.operationType(), event.payload(),
                        event.actorUserId(), event.clientSessionId(), event.acceptedAt()));

        log.info("[NOTIFY] broadcast op {} doc {}", event.operationId(), event.documentId());
    }
}
