package com.mwang.backend.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.collaboration.RedisCollaborationEventPublisher;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.redis.RedisConnectionFailureException;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KafkaOperationNotificationConsumerTest {

    private KafkaOperationNotificationConsumer consumer;
    private ObjectMapper objectMapper;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private RedisCollaborationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        eventPublisher = mock(RedisCollaborationEventPublisher.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        consumer = new KafkaOperationNotificationConsumer(objectMapper, redisTemplate, eventPublisher);
    }

    private String buildMessage(UUID operationId) throws JsonProcessingException {
        KafkaAcceptedOperationEvent event = new KafkaAcceptedOperationEvent(
                operationId, UUID.randomUUID(), UUID.randomUUID(),
                "sess-1", 0L, 1L, DocumentOperationType.INSERT_TEXT,
                objectMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}"),
                Instant.now());
        return objectMapper.writeValueAsString(event);
    }

    @Test
    void processesValidEventWithoutThrowing() throws Exception {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        assertThatNoException().isThrownBy(() -> consumer.onAcceptedOperation(buildMessage(UUID.randomUUID())));
    }

    @Test
    void broadcastsNewOperationViaRedis() throws Exception {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        consumer.onAcceptedOperation(buildMessage(UUID.randomUUID()));

        verify(eventPublisher).publishAcceptedOperation(any(UUID.class), any(AcceptedOperationResponse.class));
    }

    @Test
    void skipsDuplicateOperationId() throws Exception {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        consumer.onAcceptedOperation(buildMessage(UUID.randomUUID()));

        verify(eventPublisher, never()).publishAcceptedOperation(any(), any());
    }

    @Test
    void dedupKeyContainsOperationId() throws Exception {
        UUID operationId = UUID.randomUUID();
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        consumer.onAcceptedOperation(buildMessage(operationId));

        verify(valueOps).setIfAbsent(
                eq("dedup:op:" + operationId),
                eq("1"),
                eq(Duration.ofMinutes(5)));
    }

    @Test
    void broadcastsWhenRedisDeduplicationUnavailable() throws Exception {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("Redis down"));

        assertThatNoException().isThrownBy(() -> consumer.onAcceptedOperation(buildMessage(UUID.randomUUID())));
        verify(eventPublisher).publishAcceptedOperation(any(UUID.class), any(AcceptedOperationResponse.class));
    }

    @Test
    void malformedJsonPropagatesException() {
        assertThatThrownBy(() -> consumer.onAcceptedOperation("not-valid-json {{{"))
                .isInstanceOf(JsonProcessingException.class);
    }
}
