package com.mwang.backend.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.domain.DocumentOperationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;

class KafkaOperationNotificationConsumerTest {

    private KafkaOperationNotificationConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        consumer = new KafkaOperationNotificationConsumer(objectMapper);
    }

    @Test
    void processesValidEventWithoutThrowing() throws Exception {
        KafkaAcceptedOperationEvent event = new KafkaAcceptedOperationEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "sess-1", 0L, 1L, DocumentOperationType.INSERT_TEXT,
                objectMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}"),
                Instant.now());
        String message = objectMapper.writeValueAsString(event);

        assertThatNoException().isThrownBy(() -> consumer.onAcceptedOperation(message));
    }

    @Test
    void doesNotThrowOnMalformedMessage() {
        assertThatNoException().isThrownBy(() -> consumer.onAcceptedOperation("not-valid-json {{{"));
    }
}
