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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@SpringBootTest
@TestPropertySource(properties = {
        "kafka.consumer.group-id.notification=fault-tolerance-test-group",
        "spring.kafka.consumer.auto-offset-reset=latest"
})
class KafkaConsumerFaultToleranceTest extends AbstractIntegrationTest {

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @MockitoSpyBean
    RedisCollaborationEventPublisher collaborationEventPublisher;

    private Consumer<String, String> dltConsumer;

    @BeforeEach
    void setUp() throws Exception {
        Mockito.reset(collaborationEventPublisher);
        for (MessageListenerContainer container : kafkaListenerEndpointRegistry.getAllListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, 1);
        }

        Map<String, Object> props = new HashMap<>(KafkaTestUtils.consumerProps(
                KAFKA.getBootstrapServers(), "test-dlt-group-" + UUID.randomUUID(), "true"));
        props.put("auto.offset.reset", "latest");
        dltConsumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        dltConsumer.subscribe(List.of("document-operations.DLT"));
        dltConsumer.poll(Duration.ofMillis(500));
    }

    @AfterEach
    void tearDown() {
        dltConsumer.close();
    }

    @Test
    void poisonMessageGoesToDltAndConsumerContinues() throws Exception {
        kafkaTemplate.send("document-operations",
                UUID.randomUUID().toString(), "not-valid-json {{{").get();

        ConsumerRecords<String, String> dltRecords =
                KafkaTestUtils.getRecords(dltConsumer, Duration.ofSeconds(30));
        assertThat(dltRecords.count()).isGreaterThanOrEqualTo(1);

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

        kafkaTemplate.send("document-operations", key, message).get();
        kafkaTemplate.send("document-operations", key, message).get();

        Thread.sleep(3000);

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
