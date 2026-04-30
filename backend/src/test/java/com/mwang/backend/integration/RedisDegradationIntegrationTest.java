package com.mwang.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.mwang.backend.collaboration.RedisCollaborationChannels;
import com.mwang.backend.collaboration.RedisCollaborationEventPublisher;
import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.repositories.UserRepository;
import com.mwang.backend.service.CurrentUserProvider;
import com.mwang.backend.service.DocumentOperationService;
import com.mwang.backend.testcontainers.AbstractIntegrationTest;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.SubmitOperationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "collaboration.redis.circuit-breaker.sliding-window-size=5",
        "collaboration.redis.circuit-breaker.minimum-number-of-calls=5",
        "collaboration.redis.circuit-breaker.failure-rate-threshold=40",
        "collaboration.redis.circuit-breaker.wait-duration-in-open-ms=3000",
        "collaboration.outbox.poll-interval-ms=60000",
        "collaboration.redis.listener.recovery-interval-ms=500"
})
class RedisDegradationIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @Autowired
    DocumentOperationService operationService;

    @Autowired
    RedisCollaborationEventPublisher redisPublisher;

    @Autowired
    UserRepository userRepo;

    @Autowired
    DocumentRepository documentRepo;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    RedisMessageListenerContainer listenerContainer;

    @Test
    void operationsSucceedAndReadinessUnchangedDuringRedisOutage() throws Exception {
        User user = userRepo.save(User.builder()
                .username("redis-chaos-" + UUID.randomUUID())
                .email(UUID.randomUUID() + "@test.com")
                .build());
        Document doc = documentRepo.save(Document.builder()
                .title("Redis Chaos Doc")
                .content("{\"blocks\":[]}")
                .owner(user)
                .build());

        SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);
        when(accessor.getSessionId()).thenReturn("chaos-sess");
        when(currentUserProvider.requireCurrentUser(any(SimpMessageHeaderAccessor.class))).thenReturn(user);

        JsonNode payload = objectMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"x\"}");

        // Phase 1 — submit with Redis up
        for (int i = 0; i < 3; i++) {
            AcceptedOperationResponse response = operationService.submitOperation(doc.getId(),
                    new SubmitOperationRequest(UUID.randomUUID(), (long) i, DocumentOperationType.INSERT_TEXT, payload),
                    accessor);
            assertThatCode(() -> redisPublisher.publishAcceptedOperation(doc.getId(), response))
                    .doesNotThrowAnyException();
        }

        // Register subscriber before the outage so we prove pre-existing subscriptions are rebound
        String opsChannel = RedisCollaborationChannels.documentOperations(doc.getId());
        CountDownLatch subscriberLatch = new CountDownLatch(1);
        MessageListener testListener = (msg, pattern) -> subscriberLatch.countDown();
        listenerContainer.addMessageListener(testListener, new PatternTopic(opsChannel));

        // Phase 2 — stop Redis (not pause; stop drops TCP connections and forces a real reconnect)
        DockerClient docker = REDIS.getDockerClient();
        docker.stopContainerCmd(REDIS.getContainerId()).withTimeout(0).exec();

        try {
            // Lettuce detects the TCP disconnect immediately after the process dies
            Thread.sleep(1500);

            // Phase 3 — submit with Redis down: publisher must not throw
            for (int i = 3; i < 9; i++) {
                final int version = i;
                AcceptedOperationResponse response = operationService.submitOperation(doc.getId(),
                        new SubmitOperationRequest(UUID.randomUUID(), (long) version, DocumentOperationType.INSERT_TEXT, payload),
                        accessor);
                assertThatCode(() -> redisPublisher.publishAcceptedOperation(doc.getId(), response))
                        .doesNotThrowAnyException();
            }

            // Phase 4 — readiness must stay up
            ResponseEntity<String> readiness = restTemplate.getForEntity(
                    "/actuator/health/readiness", String.class);
            assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);

        } finally {
            docker.startContainerCmd(REDIS.getContainerId()).exec();
        }

        // Phase 5 — wait past the breaker open duration (3 s) and listener recovery interval (0.5 s)
        // so Redis is accepting connections, the breaker can transition to HALF_OPEN, and the
        // listener container can rebind its subscriptions after the real reconnect
        Thread.sleep(4000);

        AcceptedOperationResponse probeResponse = operationService.submitOperation(doc.getId(),
                new SubmitOperationRequest(UUID.randomUUID(), 9L, DocumentOperationType.INSERT_TEXT, payload),
                accessor);
        redisPublisher.publishAcceptedOperation(doc.getId(), probeResponse);

        assertThat(subscriberLatch.await(8, TimeUnit.SECONDS))
                .as("pre-existing subscription must rebind and receive published events after Redis and breaker recover")
                .isTrue();

        listenerContainer.removeMessageListener(testListener);

        // Phase 5 (continued) — publisher continues to work without throwing
        for (int i = 10; i < 12; i++) {
            final int version = i;
            AcceptedOperationResponse response = operationService.submitOperation(doc.getId(),
                    new SubmitOperationRequest(UUID.randomUUID(), (long) version, DocumentOperationType.INSERT_TEXT, payload),
                    accessor);
            assertThatCode(() -> redisPublisher.publishAcceptedOperation(doc.getId(), response))
                    .doesNotThrowAnyException();
        }
    }
}
