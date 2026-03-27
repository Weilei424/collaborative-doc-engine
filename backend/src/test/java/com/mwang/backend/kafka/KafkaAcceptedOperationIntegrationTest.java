package com.mwang.backend.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.domain.DocumentOperationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@SpringBootTest(
        properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
)
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = "${kafka.topics.document-operations:document-operations}")
class KafkaAcceptedOperationIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoSpyBean
    private KafkaOperationNotificationConsumer consumer;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${kafka.topics.document-operations}")
    private String operationsTopic;

    @Test
    void consumerReceivesPublishedOperationEvent() throws Exception {
        UUID documentId = UUID.randomUUID();
        KafkaAcceptedOperationEvent event = new KafkaAcceptedOperationEvent(
                UUID.randomUUID(), documentId, UUID.randomUUID(),
                "sess-it", 0L, 1L, DocumentOperationType.INSERT_TEXT,
                objectMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hello\"}"),
                Instant.now());

        kafkaTemplate.send(operationsTopic, documentId.toString(),
                objectMapper.writeValueAsString(event));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                verify(consumer).onAcceptedOperation(any()));
    }
}
