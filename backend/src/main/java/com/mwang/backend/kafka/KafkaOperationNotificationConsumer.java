package com.mwang.backend.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaOperationNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaOperationNotificationConsumer.class);

    private final ObjectMapper objectMapper;

    public KafkaOperationNotificationConsumer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${kafka.topics.document-operations}",
            groupId = "${kafka.consumer.group-id.notification:notification-consumer-group}"
    )
    public void onAcceptedOperation(String message) {
        try {
            KafkaAcceptedOperationEvent event =
                    objectMapper.readValue(message, KafkaAcceptedOperationEvent.class);
            log.info("[NOTIFY] documentId={} actorUserId={} serverVersion={} operationType={}" +
                            " — would notify collaborators",
                    event.documentId(), event.actorUserId(),
                    event.serverVersion(), event.operationType());
        } catch (Exception e) {
            log.error("[NOTIFY] Failed to deserialize accepted operation event: rawMessage={}",
                    message, e);
        }
    }
}
