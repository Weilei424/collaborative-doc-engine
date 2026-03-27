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
        publisher = new RedisTemplateCollaborationEventPublisher(redisTemplate, objectMapper, INSTANCE_ID);
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

    private AcceptedOperationResponse buildResponse(UUID documentId) {
        return new AcceptedOperationResponse(
                UUID.randomUUID(), documentId, 1L,
                DocumentOperationType.INSERT_TEXT, null,
                UUID.randomUUID(), "session-1", Instant.now()
        );
    }
}
