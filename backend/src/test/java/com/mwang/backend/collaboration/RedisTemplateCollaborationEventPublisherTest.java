package com.mwang.backend.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RedisTemplateCollaborationEventPublisherTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private ObjectMapper objectMapper;
    private RedisTemplateCollaborationEventPublisher publisher;
    private static final String INSTANCE_ID = "test-instance";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        io.micrometer.core.instrument.Counter counter =
                io.micrometer.core.instrument.Counter.builder("redis.circuit_open").register(registry);
        io.github.resilience4j.circuitbreaker.CircuitBreaker closedBreaker =
                io.github.resilience4j.circuitbreaker.CircuitBreaker.of(
                        "test",
                        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.ofDefaults());
        publisher = new RedisTemplateCollaborationEventPublisher(
                redisTemplate, objectMapper, INSTANCE_ID, registry, closedBreaker, counter);
    }

    @Test
    void publishAcceptedOperation_sendsToDocumentOperationsChannel() {
        UUID documentId = UUID.randomUUID();

        publisher.publishAcceptedOperation(documentId, buildResponse(documentId));

        verify(redisTemplate).convertAndSend(
                eq(RedisCollaborationChannels.documentOperations(documentId)),
                anyString()
        );
    }

    @Test
    void publishAcceptedOperation_wrapsPayloadWithPublisherInstanceId() throws Exception {
        UUID documentId = UUID.randomUUID();
        AcceptedOperationResponse response = buildResponse(documentId);

        publisher.publishAcceptedOperation(documentId, response);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(anyString(), captor.capture());
        RedisAcceptedOperationEvent event = objectMapper.readValue(captor.getValue(), RedisAcceptedOperationEvent.class);
        assertThat(event.publisherInstanceId()).isEqualTo(INSTANCE_ID);
        assertThat(event.payload().documentId()).isEqualTo(documentId);
    }

    @Test
    void publishAcceptedOperation_recordsPublishRedisTimer() {
        UUID documentId = UUID.randomUUID();
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        io.micrometer.core.instrument.Counter timerCounter =
                io.micrometer.core.instrument.Counter.builder("redis.circuit_open").register(registry);
        io.github.resilience4j.circuitbreaker.CircuitBreaker closedBreaker =
                io.github.resilience4j.circuitbreaker.CircuitBreaker.of(
                        "timer-test",
                        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.ofDefaults());
        RedisTemplateCollaborationEventPublisher pub =
                new RedisTemplateCollaborationEventPublisher(
                        redisTemplate, objectMapper, INSTANCE_ID, registry, closedBreaker, timerCounter);

        pub.publishAcceptedOperation(documentId, buildResponse(documentId));

        assertThat(registry.find("publishRedis").timer().count()).isEqualTo(1);
    }

    private AcceptedOperationResponse buildResponse(UUID documentId) {
        return new AcceptedOperationResponse(
                UUID.randomUUID(), documentId, 1L,
                DocumentOperationType.INSERT_TEXT, null,
                UUID.randomUUID(), "session-1", Instant.now()
        );
    }
}
