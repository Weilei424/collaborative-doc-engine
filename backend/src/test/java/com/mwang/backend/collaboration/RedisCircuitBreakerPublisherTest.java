package com.mwang.backend.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.PresenceEventResponse;
import com.mwang.backend.web.model.PresenceType;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RedisCircuitBreakerPublisherTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private CircuitBreaker circuitBreaker;
    private Counter counter;
    private RedisTemplateCollaborationEventPublisher publisher;

    private static final UUID DOC_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        counter = Counter.builder("redis.circuit_open").register(registry);
        circuitBreaker = CircuitBreaker.of("test", CircuitBreakerConfig.ofDefaults());
        publisher = new RedisTemplateCollaborationEventPublisher(
                redisTemplate, new ObjectMapper().findAndRegisterModules(),
                "test-instance", registry, circuitBreaker, counter);
    }

    @Test
    void publishAcceptedOperation_doesNotThrowWhenBreakerIsOpen() {
        circuitBreaker.transitionToOpenState();

        assertThatCode(() -> publisher.publishAcceptedOperation(DOC_ID, buildResponse()))
                .doesNotThrowAnyException();

        verifyNoInteractions(redisTemplate);
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void publishSessionSnapshot_doesNotThrowWhenBreakerIsOpen() {
        circuitBreaker.transitionToOpenState();

        // null snapshot serializes as JSON null — safe here because the breaker is open and Redis is never reached
        assertThatCode(() -> publisher.publishSessionSnapshot(DOC_ID, null))
                .doesNotThrowAnyException();

        verifyNoInteractions(redisTemplate);
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void publishPresenceEvent_doesNotThrowWhenBreakerIsOpen() {
        circuitBreaker.transitionToOpenState();
        PresenceEventResponse event = new PresenceEventResponse(
                DOC_ID, UUID.randomUUID(), UUID.randomUUID(), "user1", PresenceType.CURSOR_POSITION, null, Instant.now());

        assertThatCode(() -> publisher.publishPresenceEvent(event))
                .doesNotThrowAnyException();

        verifyNoInteractions(redisTemplate);
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void publishAccessRevoked_doesNotThrowWhenBreakerIsOpen() {
        circuitBreaker.transitionToOpenState();
        UUID revokedUserId = UUID.randomUUID();

        assertThatCode(() -> publisher.publishAccessRevoked(DOC_ID, revokedUserId))
                .doesNotThrowAnyException();

        verifyNoInteractions(redisTemplate);
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void publishAcceptedOperation_doesNotThrowOnRedisConnectionFailure() {
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(redisTemplate).convertAndSend(anyString(), anyString());

        assertThatCode(() -> publisher.publishAcceptedOperation(DOC_ID, buildResponse()))
                .doesNotThrowAnyException();

        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        assertThat(counter.count()).isEqualTo(0.0);
    }

    @Test
    void publishAcceptedOperation_counterDoesNotIncrementOnRedisFailure() {
        doThrow(new RedisConnectionFailureException("timeout"))
                .when(redisTemplate).convertAndSend(anyString(), anyString());

        publisher.publishAcceptedOperation(DOC_ID, buildResponse());

        // counter only increments for CallNotPermittedException (open breaker), not raw failures
        assertThat(counter.count()).isEqualTo(0.0);
    }

    private AcceptedOperationResponse buildResponse() {
        return new AcceptedOperationResponse(
                UUID.randomUUID(), DOC_ID, 1L,
                DocumentOperationType.INSERT_TEXT, null,
                UUID.randomUUID(), "session-1", Instant.now());
    }
}
