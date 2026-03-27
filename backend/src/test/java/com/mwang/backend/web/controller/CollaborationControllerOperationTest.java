package com.mwang.backend.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.collaboration.RedisCollaborationEventPublisher;
import com.mwang.backend.service.CollaborationBroadcastService;
import com.mwang.backend.service.CollaborationPresenceService;
import com.mwang.backend.service.CollaborationSessionService;
import com.mwang.backend.service.DocumentOperationService;
import com.mwang.backend.service.exception.DocumentAccessDeniedException;
import com.mwang.backend.service.exception.InvalidOperationException;
import com.mwang.backend.service.exception.OperationConflictException;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.SubmitOperationRequest;
import com.mwang.backend.domain.DocumentOperationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CollaborationControllerOperationTest {

    private CollaborationController controller;
    private DocumentOperationService operationService;
    private CollaborationBroadcastService broadcastService;
    private RedisCollaborationEventPublisher redisPublisher;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        operationService = mock(DocumentOperationService.class);
        broadcastService = mock(CollaborationBroadcastService.class);
        redisPublisher = mock(RedisCollaborationEventPublisher.class);
        controller = new CollaborationController(
                mock(CollaborationSessionService.class),
                mock(CollaborationPresenceService.class),
                broadcastService,
                operationService,
                redisPublisher);
        mapper = new ObjectMapper();
    }

    @Test
    void submitOperationDelegatesToServiceAndBroadcasts() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        JsonNode payload = mapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hi\"}");
        SubmitOperationRequest request = new SubmitOperationRequest(
                operationId, 0L, DocumentOperationType.INSERT_TEXT, payload);
        AcceptedOperationResponse response = new AcceptedOperationResponse(
                operationId, documentId, 1L, DocumentOperationType.INSERT_TEXT, payload,
                UUID.randomUUID(), "", Instant.now());
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionAttributes(new java.util.HashMap<>());

        when(operationService.submitOperation(eq(documentId), eq(request), any())).thenReturn(response);

        controller.submitOperation(documentId, request, accessor);

        verify(operationService).submitOperation(eq(documentId), eq(request), any());
        verify(broadcastService).broadcastAcceptedOperation(documentId, response);
        verify(redisPublisher).publishAcceptedOperation(documentId, response);
    }

    @Test
    void submitOperationPropagatesAccessDenied() throws Exception {
        UUID documentId = UUID.randomUUID();
        JsonNode payload = mapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"x\"}");
        SubmitOperationRequest request = new SubmitOperationRequest(
                UUID.randomUUID(), 0L, DocumentOperationType.INSERT_TEXT, payload);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionAttributes(new java.util.HashMap<>());

        when(operationService.submitOperation(eq(documentId), eq(request), any()))
                .thenThrow(new DocumentAccessDeniedException(documentId, UUID.randomUUID()));

        assertThatThrownBy(() -> controller.submitOperation(documentId, request, accessor))
                .isInstanceOf(DocumentAccessDeniedException.class);
        verifyNoInteractions(broadcastService, redisPublisher);
    }

    @Test
    void submitOperationPropagatesInvalidOperation() throws Exception {
        UUID documentId = UUID.randomUUID();
        JsonNode payload = mapper.createObjectNode();
        SubmitOperationRequest request = new SubmitOperationRequest(
                UUID.randomUUID(), 0L, DocumentOperationType.INSERT_TEXT, payload);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionAttributes(new java.util.HashMap<>());

        when(operationService.submitOperation(eq(documentId), eq(request), any()))
                .thenThrow(new InvalidOperationException("invalid payload"));

        assertThatThrownBy(() -> controller.submitOperation(documentId, request, accessor))
                .isInstanceOf(InvalidOperationException.class);
        verifyNoInteractions(broadcastService, redisPublisher);
    }

    @Test
    void submitOperationPropagatesOperationConflict() throws Exception {
        UUID documentId = UUID.randomUUID();
        JsonNode payload = mapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"x\"}");
        SubmitOperationRequest request = new SubmitOperationRequest(
                UUID.randomUUID(), 0L, DocumentOperationType.INSERT_TEXT, payload);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionAttributes(new java.util.HashMap<>());

        when(operationService.submitOperation(eq(documentId), eq(request), any()))
                .thenThrow(new OperationConflictException("conflict"));

        assertThatThrownBy(() -> controller.submitOperation(documentId, request, accessor))
                .isInstanceOf(OperationConflictException.class);
        verifyNoInteractions(broadcastService, redisPublisher);
    }
}
