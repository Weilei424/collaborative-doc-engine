package com.mwang.backend.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.collaboration.RedisCollaborationEventPublisher;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.testcontainers.AbstractIntegrationTest;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@SpringBootTest
class KafkaConsumerFaultToleranceTest extends AbstractIntegrationTest {

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper objectMapper;

    @MockitoSpyBean
    RedisCollaborationEventPublisher collaborationEventPublisher;

    private Consumer<String, String> dltConsumer;

    @BeforeEach
    void setUp() {
        Mockito.reset(collaborationEventPublisher);

        Map<String, Object> props = KafkaTestUtils.consumerProps(
                KAFKA.getBootstrapServers(), "test-dlt-group-" + UUID.randomUUID(), "true");
        dltConsumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        dltConsumer.subscribe(List.of("document-operations.DLT"));
    }

    @AfterEach
    void tearDown() {
        dltConsumer.close();
    }

    @Test
    void poisonMessageGoesToDltAndConsumerContinues() throws Exception {
        // Publish a non-JSON poison message — JsonProcessingException is non-retryable → straight to DLT
        kafkaTemplate.send("document-operations",
                UUID.randomUUID().toString(), "not-valid-json {{{").get();

        // DLT must receive the record (allow up to 30s)
        ConsumerRecords<String, String> dltRecords =
                KafkaTestUtils.getRecords(dltConsumer, Duration.ofSeconds(30));
        assertThat(dltRecords.count()).isGreaterThanOrEqualTo(1);

        // Consumer group must not be stalled: publish a valid message and verify it is processed
        KafkaAcceptedOperationEvent validEvent = buildEvent();
        kafkaTemplate.send("document-operations",
                validEvent.documentId().toString(),
                objectMapper.writeValueAsString(validEvent)).get();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                verify(collaborationEventPublisher)
                        .publishAcceptedOperation(eq(validEvent.documentId()), any(AcceptedOperationResponse.class)));
    }

    @Test
    void validMessageIsBroadcastViaRedis() throws Exception {
        KafkaAcceptedOperationEvent event = buildEvent();
        kafkaTemplate.send("document-operations",
                event.documentId().toString(),
                objectMapper.writeValueAsString(event)).get();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                verify(collaborationEventPublisher)
                        .publishAcceptedOperation(eq(event.documentId()), any(AcceptedOperationResponse.class)));
    }

    @Test
    void duplicateOperationIdIsNotBroadcastTwice() throws Exception {
        KafkaAcceptedOperationEvent event = buildEvent();
        String message = objectMapper.writeValueAsString(event);
        String key = event.documentId().toString();

        // Send the same event twice (simulate at-least-once re-delivery)
        kafkaTemplate.send("document-operations", key, message).get();
        kafkaTemplate.send("document-operations", key, message).get();

        // Wait long enough for both messages to be consumed before asserting
        Thread.sleep(3000);

        // publishAcceptedOperation must have been called exactly once (dedup suppressed the second)
        verify(collaborationEventPublisher, Mockito.times(1))
                .publishAcceptedOperation(eq(event.documentId()), any(AcceptedOperationResponse.class));
    }

    private KafkaAcceptedOperationEvent buildEvent() throws Exception {
        return new KafkaAcceptedOperationEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "sess-fault-test", 0L, 1L, DocumentOperationType.INSERT_TEXT,
                objectMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hello\"}"),
                Instant.now());
    }
}
