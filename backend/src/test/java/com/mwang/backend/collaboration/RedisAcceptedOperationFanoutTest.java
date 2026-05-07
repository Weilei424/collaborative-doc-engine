package com.mwang.backend.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.service.CollaborationBroadcastService;
import com.mwang.backend.testcontainers.AbstractIntegrationTest;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.node.NullNode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
class RedisAcceptedOperationFanoutTest extends AbstractIntegrationTest {

    // Dedicated Redis container so batcher pub/sub messages from other contexts
    // don't interfere with the pub/sub assertions in this test.
    @SuppressWarnings("resource")
    private static final GenericContainer<?> FANOUT_REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    static {
        FANOUT_REDIS.start();
    }

    @DynamicPropertySource
    static void redisOverride(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", FANOUT_REDIS::getHost);
        registry.add("spring.data.redis.port", () -> FANOUT_REDIS.getMappedPort(6379));
        registry.add("collaboration.redis.listener.enabled", () -> "true");
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CollaborationBroadcastService collaborationBroadcastService;

    @Autowired
    @Qualifier("collaborationInstanceId")
    private String collaborationInstanceId;

    @Test
    void foreignInstanceOperation_isForwardedToLocalBroadcast() throws Exception {
        UUID documentId = UUID.randomUUID();
        AcceptedOperationResponse response = buildResponse(documentId);
        RedisAcceptedOperationEvent event = new RedisAcceptedOperationEvent("foreign-instance-id", response);

        redisTemplate.convertAndSend(
                RedisCollaborationChannels.documentOperations(documentId),
                objectMapper.writeValueAsString(event));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(collaborationBroadcastService).broadcastAcceptedOperation(documentId, response));
    }

    @Test
    void selfPublishedOperation_isNotForwardedToLocalBroadcast() throws Exception {
        UUID documentId = UUID.randomUUID();
        AcceptedOperationResponse response = buildResponse(documentId);
        RedisAcceptedOperationEvent event = new RedisAcceptedOperationEvent(collaborationInstanceId, response);

        redisTemplate.convertAndSend(
                RedisCollaborationChannels.documentOperations(documentId),
                objectMapper.writeValueAsString(event));

        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                verify(collaborationBroadcastService, never()).broadcastAcceptedOperation(documentId, response));
    }

    private AcceptedOperationResponse buildResponse(UUID documentId) {
        return new AcceptedOperationResponse(
                UUID.randomUUID(), documentId, 1L,
                DocumentOperationType.INSERT_TEXT, NullNode.instance,
                UUID.randomUUID(), "session-1", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }
}
